/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.media.application;

import de.greluc.homeinv.media.api.BlobStore;
import de.greluc.homeinv.media.api.MediaObjectStored;
import de.greluc.homeinv.media.api.MediaService;
import de.greluc.homeinv.media.api.MediaUrlSigner;
import de.greluc.homeinv.media.api.MediaView;
import de.greluc.homeinv.media.domain.Attachment;
import de.greluc.homeinv.media.domain.MediaObject;
import de.greluc.homeinv.media.domain.ScanState;
import de.greluc.homeinv.media.infrastructure.AttachmentRepository;
import de.greluc.homeinv.media.infrastructure.MediaObjectRepository;
import de.greluc.homeinv.platform.CallerContext;
import de.greluc.homeinv.platform.MediaHostCheck;
import de.greluc.homeinv.platform.NotFoundException;
import de.greluc.homeinv.platform.TenantContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Uploading, attaching and serving files.
 *
 * <h2>Why the scan is synchronous at stage 0</h2>
 *
 * <p>{@code REQ-MED-013} allows a blob to sit in {@code PENDING_SCAN} until a verdict arrives, and
 * the architecture generates derivatives in a worker. Stage 0 has no worker and no message broker —
 * RabbitMQ is stage 1 — so the scan happens in the request and the upload either succeeds having
 * been judged clean, or fails.
 *
 * <p>That is a smaller behaviour than the requirement permits, not a larger one: nothing becomes
 * retrievable without a verdict either way. The {@code PENDING_SCAN} state and its index exist from
 * the start because stage 1 moves the scan into the worker, and a state added later would be a state
 * that did not exist for everything uploaded before it.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DefaultMediaService implements MediaService {

  private final UploadPipeline pipeline;
  private final MediaObjectRepository objects;
  private final AttachmentRepository attachments;
  private final BlobStore blobs;
  private final MediaUrlSigner signer;
  private final MediaHostCheck mediaHost;
  private final ApplicationEventPublisher events;
  private final Clock clock;

  @Override
  @Transactional
  public MediaView upload(
      InputStream content, String targetKind, UUID targetId, boolean primaryImage, UUID actor)
      throws IOException {

    UUID tenantId = TenantContext.require();
    UploadPipeline.Stored stored = pipeline.accept(tenantId, content);
    Instant now = Instant.now(clock);

    // Deduplication within the tenant: the same photo uploaded twice finds the
    // first record and costs no second copy (ADR-0032). Across tenants it would
    // make "do you have this file" answerable by timing, which is why the lookup
    // is scoped.
    MediaObject object =
        objects
            .findByHash(tenantId, stored.sha256())
            .orElseGet(
                () -> {
                  MediaObject fresh =
                      MediaObject.pending(
                          UUID.randomUUID(),
                          tenantId,
                          stored.sha256(),
                          stored.mediaType(),
                          stored.byteSize(),
                          stored.widthPx(),
                          stored.heightPx(),
                          actor,
                          now);
                  // The pipeline already has a clean verdict; without it nothing
                  // would have been stored. Recording it here keeps the state
                  // machine honest rather than letting CLEAN be a default.
                  fresh.recordVerdict(ScanState.CLEAN, null, now);
                  return objects.save(fresh);
                });

    attachments
        .findLive(tenantId, object.getId(), targetKind, targetId)
        .orElseGet(
            () -> {
              object.addReference(now);
              return attachments.save(
                  Attachment.create(
                      UUID.randomUUID(),
                      tenantId,
                      object.getId(),
                      targetKind,
                      targetId,
                      primaryImage,
                      actor,
                      now));
            });

    if (object.getDerivedAt() == null) {
      // Published inside the transaction, delivered after it commits. Spring
      // Modulith writes it to `outbox.event_publication` first, so a broker that
      // is down delays the thumbnails and loses nothing — and a transaction that
      // rolls back publishes nothing, rather than asking a worker to derive
      // variants of an object that does not exist (REQ-NFR-013).
      //
      // Skipped for an object that already has its derivatives: a second upload
      // of the same photo finds the first record, and asking for the same work
      // again would be work the consumer would discard anyway.
      events.publishEvent(
          new MediaObjectStored(tenantId, object.getId(), object.getSha256()));
    }

    log.debug("Stored media {} for {} {}", object.getId(), targetKind, targetId);
    return toView(object);
  }

  @Override
  @Transactional(readOnly = true)
  public List<MediaView> attachmentsOf(String targetKind, UUID targetId) {
    UUID tenantId = TenantContext.require();
    return attachments.findLiveFor(tenantId, targetKind, targetId).stream()
        .map(
            attachment ->
                objects
                    .findLive(tenantId, attachment.getMediaObjectId())
                    .orElseThrow(
                        () ->
                            // An attachment pointing at a missing blob is a broken
                            // invariant, not a 404 for the caller: the foreign key
                            // makes it impossible, so reaching here means the row
                            // was written around it.
                            new IllegalStateException(
                                "Attachment "
                                    + attachment.getId()
                                    + " references a media object that is not there")))
        .map(this::toView)
        .toList();
  }

  @Override
  @Transactional
  public void detach(UUID mediaObjectId, String targetKind, UUID targetId, UUID actor) {
    UUID tenantId = TenantContext.require();
    Instant now = Instant.now(clock);

    Attachment attachment =
        attachments
            .findLive(tenantId, mediaObjectId, targetKind, targetId)
            .orElseThrow(() -> new NotFoundException("attachment", mediaObjectId));
    attachment.markDeleted(actor, now);

    MediaObject object =
        objects
            .findLive(tenantId, mediaObjectId)
            .orElseThrow(() -> new NotFoundException("media", mediaObjectId));

    if (object.removeReference(now)) {
      // The tenant's last reference. Another tenant holding the same bytes holds
      // its own copy and is untouched - which is the property per-tenant content
      // addressing exists to give (ADR-0032).
      try {
        blobs.delete(tenantId, object.getSha256());
      } catch (IOException failed) {
        // The row is already detached; a blob that outlives its last reference is
        // wasted space, not a correctness problem, and a failed delete must not
        // roll back the detach the user asked for.
        log.warn("Could not remove blob {} of tenant {}", object.getSha256(), tenantId, failed);
      }
    }
  }

  @Override
  public InputStream openVerified(UUID tenantId, String sha256) throws IOException {
    // No tenant context and no permission check here on purpose: the caller is the
    // media endpoint, which has already verified the signature that carries both
    // (REQ-MED-010). A second check would need a session, and the whole point of a
    // signed URL is that an <img src> carries no session.
    return blobs.open(tenantId, sha256);
  }

  /**
   * One absolute, signed URL on the media host.
   *
   * <p>Absolute, and on {@code HOMEINV_MEDIA_BASE_URL}, because a relative path would resolve
   * against the application's own origin — which is the host that must never serve tenant bytes
   * (REQ-MED-010). The variant's own content address is in the path, so each variant is its own
   * blob and two objects whose thumbnails are identical store one.
   *
   * @param object the media object
   * @param viewer the person the link is issued to
   * @param variant which variant
   * @param sha256 that variant's content address
   * @return the absolute URL including the signature
   */
  private String url(MediaObject object, UUID viewer, String variant, String sha256) {
    return mediaHost.getMediaBaseUrl()
        + "/media/"
        + object.getTenantId()
        + "/"
        + sha256
        + "/"
        + variant
        + signer.sign(object.getTenantId(), viewer, sha256, variant);
  }

  /**
   * Builds the published view, signing a URL per variant.
   *
   * @param object the stored file
   * @return the view
   */
  private MediaView toView(MediaObject object) {
    Map<String, String> urls = new LinkedHashMap<>();
    if (object.isRetrievable()) {
      // A URL is offered only for a variant that EXISTS. `full` is produced
      // during the upload - it is the re-encoded, metadata-stripped image that
      // gets stored - while `thumb` and `preview` are derived afterwards and are
      // absent until they are. Offering a link to a variant that has not been
      // produced would be worse than offering none: the client would render a
      // broken image and blame the upload (REQ-MED-005, ADR-0051).
      UUID viewer = CallerContext.require().userId();
      urls.put("full", url(object, viewer, "full", object.getSha256()));
      if (object.getPreviewSha256() != null) {
        urls.put("preview", url(object, viewer, "preview", object.getPreviewSha256()));
      }
      if (object.getThumbSha256() != null) {
        urls.put("thumb", url(object, viewer, "thumb", object.getThumbSha256()));
      }
    }
    return new MediaView(
        object.getId(),
        object.getMediaType(),
        object.getByteSize(),
        object.getWidthPx(),
        object.getHeightPx(),
        object.getScanState().name(),
        Map.copyOf(urls));
  }
}
