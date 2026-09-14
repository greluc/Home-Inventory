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
                  // NO verdict here. The object is born PENDING_SCAN and stays
                  // there until the worker has asked the scanner (ADR-0054).
                  // `MediaObject.pending` sets that state; recording CLEAN here
                  // was what made the scan look synchronous to everything
                  // downstream, including the tests.
                  //
                  // The quota is claimed here, inside the `orElseGet`, so that it
                  // is charged for a new object and not for the second upload of
                  // one the tenant already has. In this transaction, so a failure
                  // below returns the claim with the rollback.
                  quotas.require(
                      de.greluc.homeinv.tenancy.api.QuotaGuard.Quota.STORAGE_BYTES,
                      stored.byteSize());
                  return objects.save(fresh);
                });

    // The first image of a thing is its primary one unless the caller says
    // otherwise: REQ-MED-002 wants the primary selectable AND defaulted, and a
    // client that uploads one photograph should not have to send a second
    // request to make it the one lists show.
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
                      actor,
                      now));
            });

    if (object.getDerivedAt() == null) {
      // Published inside the transaction, delivered after it commits. Spring
      // Modulith writes it to `outbox.event_publication` first, so a broker that
      // is down delays the SCAN and loses nothing — and a transaction that rolls
      // back publishes nothing, rather than asking a worker to scan an object
      // that does not exist (REQ-NFR-013).
      //
      // This message is now what gets the object judged at all, not merely what
      // gets its thumbnails made. A broker outage therefore leaves uploads
      // unretrievable rather than merely thumbnail-less, which is the fail-closed
      // direction and is why `rabbitmq` is in every profile (ADR-0051).
      //
      // Skipped for an object that already has its derivatives: it also already
      // has a verdict, since nothing is derived before one.
      events.publishEvent(
          new MediaObjectStored(tenantId, object.getId(), object.getSha256()));
    }

    log.debug(
        "Accepted media {} for {} {}; it is {} until the worker has scanned it.",
        object.getId(),
        targetKind,
        targetId,
        object.getScanState());
    return toView(object, primary);
  }

  @Override
  @Transactional(readOnly = true)
  public MediaView findOne(UUID mediaObjectId) {
    UUID tenantId = TenantContext.require();

    MediaObject object =
        objects
            .findLive(tenantId, mediaObjectId)
            // Another tenant's object and one that does not exist are the same
            // answer. The query is tenant-scoped and RLS scopes it again, so
            // this is reached for both, and deliberately says nothing about
            // which (REQ-SEC-025).
            .orElseThrow(() -> new NotFoundException("media", mediaObjectId));

    // The verdict, as the status code. REQ-SEC-092 keeps `422` and `503` after
    // ADR-0054 moved the scan out of the upload — they moved with it, from the
    // POST to this GET, because that is where a client now learns the outcome.
    switch (object.getScanState()) {
      case INFECTED ->
          throw new MalwareDetectedException(object.getScanVerdict());
      case PENDING_SCAN, SCAN_FAILED ->
          throw new ScannerUnavailableException(
              "Media object " + mediaObjectId + " has no verdict yet", null);
      case CLEAN -> {
        // Falls through to the view below.
      }
    }

    return toView(object, attachments.isPrimaryAnywhere(tenantId, mediaObjectId));
  }

  @Override
  @Transactional(readOnly = true)
  public Page<MediaView> attachmentsOf(String targetKind, UUID targetId, String cursor, int limit) {
    UUID tenantId = TenantContext.require();
    int size = Math.clamp(limit, 1, MAX_PAGE);

    // The cursor belongs to one target. Without the fingerprint a cursor from an
    // item's photographs would resume a location's at the same row, which is a
    // wrong answer rather than an error (REQ-SRCH-009, REQ-SEC-106).
    String fingerprint = fingerprintOf(targetKind, targetId);

    List<Attachment> rows;
    if (cursor == null || cursor.isBlank()) {
      rows = attachments.findLiveFor(tenantId, targetKind, targetId, PageRequest.of(0, size));
    } else {
      // Throws when the cursor was tampered with or belongs to another target.
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
                                    // An attachment pointing at a missing blob is a
                                    // broken invariant, not a 404 for the caller: the
                                    // foreign key makes it impossible, so reaching
                                    // here means the row was written around it.
                                    new IllegalStateException(
                                        "Attachment "
                                            + attachment.getId()
                                            + " references a media object that is not there")),
                        attachment.isPrimaryImage()))
            .toList();

    // A cursor only when the page was full. A short page is the last one, and
    // handing out a cursor for it would make a client fetch an empty page to
    // find that out.
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
      // The separator matters here for the same reason it does in search:
      // without it two different pairs could hash alike.
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
      // The tenant's last reference. Another tenant holding the same bytes holds
      // its own copy and is untouched - which is the property per-tenant content
      // addressing exists to give (ADR-0032).
      // The bytes are the tenant's again whether or not the blob store agrees
      // below: the reference is gone, and a blob that outlives it is wasted space
      // the reconciliation of REQ-NFR-073 finds, not storage the tenant still owes.
      quotas.release(
          de.greluc.homeinv.tenancy.api.QuotaGuard.Quota.STORAGE_BYTES, object.getByteSize());
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
    //
    // The scan state IS checked, and the check is not redundant. A URL is only
    // minted for a CLEAN object, so in a system that behaves this cannot fire —
    // which is exactly the reason to have it: after ADR-0054 the bytes are in the
    // store before any verdict exists, so "no URL was minted" became the only
    // thing standing between an unjudged blob and a client. This makes it two
    // things. The lookup is by content address and without a tenant context, so
    // it goes through the repository's own tenant-scoped query.
    if (objects.findByHash(tenantId, sha256).filter(MediaObject::isRetrievable).isEmpty()) {
      // The same 404 an invalid signature gets, for the same reason.
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
        primaryImage,
        Map.copyOf(urls));
  }
}
