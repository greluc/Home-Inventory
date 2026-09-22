/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.media.application;

import de.greluc.homeinv.media.api.DeploymentBlobStore;
import de.greluc.homeinv.media.api.ResumableUploads;
import de.greluc.homeinv.media.api.UploadSessionView;
import de.greluc.homeinv.media.domain.UploadSession;
import de.greluc.homeinv.media.infrastructure.UploadSessionRepository;
import de.greluc.homeinv.platform.NotFoundException;
import de.greluc.homeinv.platform.PayloadTooLargeException;
import de.greluc.homeinv.platform.TenantContext;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * An upload that survives a broken connection (REQ-MED-008, ADR-0084).
 *
 * <h2>The two entrances end in one pipeline</h2>
 *
 * <p>When the last byte arrives, this reads the staged file back and hands it to {@link
 * MediaService#upload} — the same size check, the same magic-byte detection, the same EXIF
 * stripping, the same transcoding, the same scan, the same deduplication. Nothing about the
 * checking is repeated here, and that is the point: two entrances to one pipeline is a design, two
 * pipelines is a defect waiting for one of them to be forgotten.
 *
 * <h2>Why the bytes come back over the network</h2>
 *
 * <p>The staged file sits in the {@code blobstore} service and is read back into this process to be
 * hashed, sniffed and transcoded. It could have been promoted in place — the store could rename it
 * into the content-addressed slot — and that would save a transfer and lose the only chance to
 * look at what arrived. Every image is re-encoded to AVIF (ADR-0052) and every file's type is
 * decided by its magic bytes rather than by what the client said (REQ-MED-004), and neither can
 * happen in a store that, by contract, does not know what a blob is.
 *
 * <h2>What is not transactional, and why</h2>
 *
 * <p>Appending bytes is not a database operation and cannot be rolled back: the store is a separate
 * service behind mTLS. So the append runs outside a transaction and the row is touched only at the
 * two moments that are transactional — creation and completion. A failure in between leaves staged
 * bytes and a session that still knows about them, which is precisely the state a resumable upload
 * is supposed to be able to recover from.
 */
@Slf4j
@Service
public class DefaultResumableUploads implements ResumableUploads {

  private final UploadSessionRepository sessions;
  private final DeploymentBlobStore staging;
  private final UploadCompletion completion;
  private final Clock clock;
  private final Duration lifetime;

  /** REQ-SEC-037: the same ceiling the one-shot path enforces, from the same property. */
  @Value("${homeinv.media.max-bytes:26214400}")
  private long maxBytes;

  /**
   * Creates the service.
   *
   * @param sessions where uploads in flight are recorded
   * @param staging the deployment's own store, which holds the bytes until they are all there.
   *     Deliberately not the tenant's store: an unfinished object pushed into somebody's own
   *     bucket is litter only this deployment knows how to clean up (ADR-0074)
   * @param completion what runs when the last byte arrives — its own bean, because it is the
   *     transaction boundary and a boundary inside the class that crosses it is no boundary at all
   * @param clock the clock, so a test does not have to wait for an expiry
   * @param lifetimeHours how long an unfinished upload may sit before the sweep removes it. Two
   *     hours by default: long enough for a large file on a slow connection with a pause in the
   *     middle, short enough that an abandoned upload does not hold the volume overnight
   */
  public DefaultResumableUploads(
      UploadSessionRepository sessions,
      DeploymentBlobStore staging,
      UploadCompletion completion,
      Clock clock,
      @Value("${HOMEINV_UPLOAD_LIFETIME_HOURS:2}") long lifetimeHours) {
    this.sessions = sessions;
    this.staging = staging;
    this.completion = completion;
    this.clock = clock;
    this.lifetime = Duration.ofHours(lifetimeHours);
  }

  @Override
  @Transactional
  public UploadSessionView begin(
      String targetKind,
      UUID targetId,
      boolean primaryImage,
      String role,
      long declaredLength,
      UUID actor) {

    if (declaredLength > maxBytes) {
      // Before a byte arrives, which is what REQ-SEC-037 asks for and what the
      // one-shot path can only approximate by cutting the stream off mid-read.
      throw new PayloadTooLargeException(
          "The upload declares %d bytes; the limit is %d".formatted(declaredLength, maxBytes));
    }

    Instant now = Instant.now(clock);
    UploadSession session =
        sessions.save(
            UploadSession.begin(
                UUID.randomUUID(),
                TenantContext.require(),
                targetKind,
                targetId,
                primaryImage,
                role == null ? "PHOTO" : role,
                declaredLength,
                now.plus(lifetime),
                actor,
                now));
    return new UploadSessionView(session.getId(), 0, declaredLength, session.getExpiresAt(), null);
  }

  @Override
  @Transactional(readOnly = true)
  public UploadSessionView status(UUID uploadId) {
    UploadSession session = live(uploadId);
    return UploadCompletion.view(session, offsetOf(session));
  }

  @Override
  public UploadSessionView append(UUID uploadId, long offset, InputStream content, UUID actor)
      throws IOException {

    UploadSession session = load(uploadId);
    if (session.isComplete()) {
      // Everything already arrived, and this is a client repeating a request
      // whose answer it lost. Told where it is rather than refused: a refusal
      // would send it back to the beginning of a file that is already stored.
      return UploadCompletion.view(session, session.getDeclaredLength());
    }
    long staged = staging.append(session.getTenantId(), uploadId, offset, content);
    if (staged > session.getDeclaredLength()) {
      // More than was promised. The bytes are discarded rather than trimmed:
      // a client that sent the wrong amount does not know what it sent, and a
      // file assembled out of a guess is worse than one that failed.
      staging.deleteStaged(session.getTenantId(), uploadId);
      throw new PayloadTooLargeException(
          "The upload declared %d bytes and has sent %d"
              .formatted(session.getDeclaredLength(), staged));
    }
    if (staged < session.getDeclaredLength()) {
      return UploadCompletion.view(session, staged);
    }
    return completion.complete(uploadId, actor);
  }

  @Override
  @Transactional
  public void abort(UUID uploadId) {
    UploadSession session = live(uploadId);
    discard(session.getTenantId(), uploadId);
    sessions.delete(session);
  }

  /**
   * The session, whether or not it has expired.
   *
   * @param uploadId the upload
   * @return the session
   * @throws NotFoundException when this tenant has no such upload
   */
  private UploadSession load(UUID uploadId) {
    return sessions
        .findOwned(TenantContext.require(), uploadId)
        .orElseThrow(() -> new NotFoundException("upload", uploadId));
  }

  /**
   * The session, provided it is still worth continuing.
   *
   * <p>An expired upload answers the same as one that never existed. It is not an oracle question —
   * the caller created it — but the answer a client needs is the same either way: this is gone,
   * begin again.
   *
   * @param uploadId the upload
   * @return the session
   * @throws NotFoundException when there is no such upload, or its time is up
   */
  private UploadSession live(UUID uploadId) {
    UploadSession session = load(uploadId);
    if (!session.isComplete() && session.getExpiresAt().isBefore(Instant.now(clock))) {
      throw new NotFoundException("upload", uploadId);
    }
    return session;
  }

  /**
   * What the store says has arrived.
   *
   * @param session the upload
   * @return the offset, or zero when nothing has been staged yet
   */
  private long offsetOf(UploadSession session) {
    if (session.isComplete()) {
      return session.getDeclaredLength();
    }
    OptionalLong staged = staging.staged(session.getTenantId(), session.getId());
    return staged.orElse(0L);
  }

  /**
   * Throws the staged bytes away, and does not fail if they are already gone.
   *
   * @param tenantId the owning tenant
   * @param uploadId the upload
   */
  private void discard(UUID tenantId, UUID uploadId) {
    try {
      staging.deleteStaged(tenantId, uploadId);
    } catch (IOException failed) {
      // Logged rather than thrown. Every caller of this has already done the
      // thing that mattered — stored the file, or abandoned the upload — and a
      // staged file nobody removed is reclaimed by the sweep.
      log.warn("Staged bytes could not be discarded; the sweep will take them", failed);
    }
  }
}
