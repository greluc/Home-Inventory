/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.media.application;

import de.greluc.homeinv.platform.Page;
import de.greluc.homeinv.media.api.BlobStore;
import de.greluc.homeinv.media.api.MalwareDetectedException;
import de.greluc.homeinv.media.api.MediaObjectStored;
import de.greluc.homeinv.media.api.MediaService;
import de.greluc.homeinv.media.api.MediaUrlSigner;
import de.greluc.homeinv.media.api.MediaView;
import de.greluc.homeinv.media.api.ScannerUnavailableException;
import de.greluc.homeinv.media.domain.Attachment;
import de.greluc.homeinv.media.domain.MediaObject;
import de.greluc.homeinv.media.infrastructure.AttachmentRepository;
import de.greluc.homeinv.media.infrastructure.MediaObjectRepository;
import de.greluc.homeinv.platform.CallerContext;
import de.greluc.homeinv.platform.CursorCodec;
import de.greluc.homeinv.platform.MediaHostCheck;
import de.greluc.homeinv.platform.NotFoundException;
import de.greluc.homeinv.platform.TenantContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Uploading, attaching and serving files.
 *
 * <h2>The upload is answered before the scan has run</h2>
 *
 * <p>{@code POST} returns {@code 202} with the object in {@code PENDING_SCAN} and a {@code Location}
 * pointing at it; the scan and the derivatives both happen in {@code worker}, over the broker
 * ADR-0051 moved to stage 0 (REQ-MED-005, REQ-MED-013, ADR-0054). The client polls that URL until it
 * answers {@code 200}.
 *
 * <p>This class scanned inside the request until 2026-09-12 and returned {@code 201}. It was a
 * deliberate choice and it was wrong on the only ground that counts: ADR-0024 and 06 §6.4 say the
 * scan runs in the worker and put {@code clamd} on the {@code scanner} segment, which {@code api} is
 * not a member of — so every upload in the generated deployment answered {@code 503}, while every
 * test passed against a stubbed scanner.
 *
 * <p>Nothing became weaker. No URL is minted for an object that is not {@code CLEAN}, the serving
 * path checks the state again, and for an image the stored bytes are the re-encoding rather than
 * what arrived.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DefaultMediaService implements MediaService {

  /** The cap REQ-NFR-010 puts on every page of every collection. */
  private static final int MAX_PAGE = 200;

  private final UploadPipeline pipeline;
  private final MediaObjectRepository objects;
  private final AttachmentRepository attachments;
  private final BlobStore blobs;
  private final MediaUrlSigner signer;
  private final MediaHostCheck mediaHost;
  private final CursorCodec cursors;
  private final ApplicationEventPublisher events;

  /**
   * What the tenant may store (REQ-TEN-009).
   *
   * <p>Claimed on the bytes of a NEW object and not on every attachment: deduplication within the
   * tenant means the same photograph attached to five items costs its size once (ADR-0032), and a
   * quota that charged five times would be counting references rather than storage.
   */
  private final de.greluc.homeinv.tenancy.api.QuotaGuard quotas;

  private final Clock clock;

  @Override
  @Transactional
  public MediaView upload(
      InputStream content,
      String targetKind,
      UUID targetId,
      boolean primaryImage,
      String role,
      UUID actor)
      throws IOException {

    UUID tenantId = TenantContext.require();
    UploadPipeline.Stored stored = pipeline.accept(tenantId, content);
    Instant now = Instant.now(clock);

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
                  quotas.require(
                      de.greluc.homeinv.tenancy.api.QuotaGuard.Quota.STORAGE_BYTES,
                      stored.byteSize());
                  return objects.save(fresh);
                });

    boolean primary = primaryImage || !attachments.hasPrimary(tenantId, targetKind, targetId);

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
                      primary,
                      role,
                      actor,
                      now));
            });

    if (object.getDerivedAt() == null) {
      events.publishEvent(
          new MediaObjectStored(tenantId, object.getId(), object.getSha256()));
    }

    log.debug(
        "Accepted media {} for {} {}; it is {} until the worker has scanned it.",
        object.getId(),
        targetKind,
        targetId,
        object.getScanState());
    return toView(object, primary, role);
  }

  @Override
  @Transactional(readOnly = true)
  public MediaView findOne(UUID mediaObjectId) {
    UUID tenantId = TenantContext.require();

    MediaObject object =
        objects
            .findLive(tenantId, mediaObjectId)
            .orElseThrow(() -> new NotFoundException("media", mediaObjectId));

    switch (object.getScanState()) {
      case INFECTED ->
          throw new MalwareDetectedException(object.getScanVerdict());
      case PENDING_SCAN, SCAN_FAILED ->
          throw new ScannerUnavailableException(
              "Media object " + mediaObjectId + " has no verdict yet", null);
      case CLEAN -> {
      }
    }

    return toView(object, attachments.isPrimaryAnywhere(tenantId, mediaObjectId));
  }

  @Override
  @Transactional(readOnly = true)
  public Page<MediaView> attachmentsOf(String targetKind, UUID targetId, String cursor, int limit) {
    UUID tenantId = TenantContext.require();
    int size = Math.clamp(limit, 1, MAX_PAGE);

    String fingerprint = fingerprintOf(targetKind, targetId);

    List<Attachment> rows;
    if (cursor == null || cursor.isBlank()) {
      rows = attachments.findLiveFor(tenantId, targetKind, targetId, PageRequest.of(0, size));
    } else {
      CursorCodec.Position after = cursors.decode(cursor, fingerprint);
      rows =
          attachments.findLiveForAfter(
              tenantId, targetKind, targetId, after.createdAt(), after.id(),
              PageRequest.of(0, size));
    }

    List<MediaView> views =
        rows.stream()
            .map(
                attachment ->
                    toView(
                        objects
                            .findLive(tenantId, attachment.getMediaObjectId())
                            .orElseThrow(
                                () ->
                                    new IllegalStateException(
                                        "Attachment "
                                            + attachment.getId()
                                            + " references a media object that is not there")),
                        attachment.isPrimaryImage(),
                        attachment.getRole()))
            .toList();

    String nextCursor = null;
    if (rows.size() == size) {
      Attachment last = rows.get(rows.size() - 1);
      nextCursor =
          cursors.encode(CursorCodec.Position.of(last.getCreatedAt(), last.getId()), fingerprint);
    }
    return Page.of(views, nextCursor);
  }

  /**
   * A stable fingerprint of the listing a cursor belongs to.
   *
   * @param targetKind {@code ITEM} or {@code LOCATION}
   * @param targetId the target
   * @return a hex digest identifying this listing
   */
  private static String fingerprintOf(String targetKind, UUID targetId) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash =
          digest.digest(("media " + targetKind + " " + targetId).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash, 0, 16);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
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
      quotas.release(
          de.greluc.homeinv.tenancy.api.QuotaGuard.Quota.STORAGE_BYTES, object.getByteSize());
      try {
        blobs.delete(tenantId, object.getSha256());
      } catch (IOException failed) {
        log.warn("Could not remove blob {} of tenant {}", object.getSha256(), tenantId, failed);
      }
    }
  }

  @Override
  public InputStream openVerified(UUID tenantId, String sha256) throws IOException {
    if (objects.findByHash(tenantId, sha256).filter(MediaObject::isRetrievable).isEmpty()) {
      throw new NotFoundException("media", (UUID) null);
    }
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
  private MediaView toView(MediaObject object, boolean primaryImage) {
    return toView(object, primaryImage, null);
  }

  /**
   * Builds the published view, signing a URL per variant.
   *
   * @param object the stored file
   * @param primaryImage whether this attachment is the one lists show
   * @param role what the attachment is for, or null for {@code PHOTO}
   * @return the view
   */
  private MediaView toView(MediaObject object, boolean primaryImage, String role) {
    Map<String, String> urls = new LinkedHashMap<>();
    if (object.isRetrievable()) {
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
        primaryImage,
        role == null || role.isBlank() ? "PHOTO" : role,
        Map.copyOf(urls));
  }
}
