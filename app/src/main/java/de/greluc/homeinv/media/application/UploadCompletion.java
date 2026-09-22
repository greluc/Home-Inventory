/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.media.application;

import de.greluc.homeinv.media.api.DeploymentBlobStore;
import de.greluc.homeinv.media.api.MediaService;
import de.greluc.homeinv.media.api.MediaView;
import de.greluc.homeinv.media.api.UploadSessionView;
import de.greluc.homeinv.media.domain.UploadSession;
import de.greluc.homeinv.media.infrastructure.UploadSessionRepository;
import de.greluc.homeinv.platform.NotFoundException;
import de.greluc.homeinv.platform.TenantContext;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The last byte has arrived; run the pipeline (REQ-MED-008, ADR-0084).
 *
 * <h2>Why this is its own bean</h2>
 *
 * <p>It is the transaction boundary, and a transaction boundary inside the class that crosses it is
 * no boundary at all: Spring applies {@code @Transactional} through a proxy, and a {@code this.}
 * call goes straight past it. {@link DefaultResumableUploads} appends bytes outside any transaction
 * — the store is a separate service over mTLS and nothing rolls it back — and then calls this,
 * where storing the file, claiming the quota, creating the attachment and recording what the upload
 * became are one transaction or none.
 *
 * <p>*`OrphanedBlobSweep` carries the same note for the same reason, having shipped with a
 * `@Transactional` that did nothing.*
 *
 * <h2>Why the bytes come back over the network</h2>
 *
 * <p>The staged file is read back into this process to be hashed, sniffed and transcoded. It could
 * have been promoted in place — the store could rename it into the content-addressed slot — and
 * that would save a transfer and lose the only chance to look at what arrived. Every image is
 * re-encoded to AVIF ([ADR-0052](../../../../../../../docs/adr/0052-one-stored-image-format.md))
 * and every file's type is decided by its magic bytes rather than by what the client said
 * (REQ-MED-004), and neither can happen in a store that, by contract, does not know what a blob is.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UploadCompletion {

  private final UploadSessionRepository sessions;
  private final DeploymentBlobStore staging;
  private final MediaService media;
  private final Clock clock;

  /**
   * Turns a finished upload into a stored, attached file.
   *
   * <p>Idempotent: a client whose response was lost asks again and is told the same media id rather
   * than uploading the file a second time.
   *
   * @param uploadId the upload
   * @param actor who finished it
   * @return the upload, now carrying the media id
   * @throws IOException when the staged bytes cannot be read
   * @throws NotFoundException when this tenant has no such upload
   */
  @Transactional
  public UploadSessionView complete(UUID uploadId, UUID actor) throws IOException {
    UUID tenantId = TenantContext.require();
    UploadSession session =
        sessions
            .findOwned(tenantId, uploadId)
            .orElseThrow(() -> new NotFoundException("upload", uploadId));
    if (session.isComplete()) {
      return view(session, session.getDeclaredLength());
    }

    MediaView stored;
    try (InputStream assembled = staging.openStaged(tenantId, uploadId)) {
      stored =
          media.upload(
              assembled,
              session.getTargetKind(),
              session.getTargetId(),
              session.isPrimaryImage(),
              session.getRole(),
              actor);
    }

    session.completed(stored.id(), actor, Instant.now(clock));
    sessions.save(session);

    // After the row and not before: staged bytes whose session was never
    // completed are removed by the sweep, while a session pointing at bytes that
    // are already gone could never be finished at all. A failure here therefore
    // costs disk space until the sweep runs, which is the recoverable half.
    try {
      staging.deleteStaged(tenantId, uploadId);
    } catch (IOException failed) {
      log.warn("Staged bytes could not be discarded; the sweep will take them", failed);
    }
    return view(session, session.getDeclaredLength());
  }

  /**
   * One upload, as a client sees it.
   *
   * @param session the upload
   * @param offset how much has arrived
   * @return the view
   */
  static UploadSessionView view(UploadSession session, long offset) {
    return new UploadSessionView(
        session.getId(),
        offset,
        session.getDeclaredLength(),
        session.getExpiresAt(),
        session.getMediaObjectId());
  }
}
