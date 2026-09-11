/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.media.application;

import de.greluc.homeinv.media.api.BlobStore;
import de.greluc.homeinv.media.api.ImageProcessor;
import de.greluc.homeinv.media.domain.MediaObject;
import de.greluc.homeinv.media.infrastructure.MediaObjectRepository;
import de.greluc.homeinv.platform.TenantContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Produces the {@code thumb} and {@code preview} derivatives of a stored image (REQ-MED-005).
 *
 * <h2>Where this runs</h2>
 *
 * <p>In the {@code worker}, off the request thread. An upload already holds a ClamAV round trip
 * with a sixty-second ceiling and one AVIF encode for the {@code full} variant; two more encodes on
 * the same thread would make the worst case minutes, on the profile with the least hardware, and
 * the visible symptom is a phone that appears to hang while photographing (ADR-0051).
 *
 * <h2>Idempotent, because delivery is at-least-once</h2>
 *
 * <p>The outbox redelivers after a broker outage, so this runs twice for the same object as a
 * matter of course. {@code derived_at} is the guard, and it is set even when nothing was produced —
 * a PDF has no derivatives and must not be claimed again for ever.
 *
 * <h2>A failure here is not a failure of the upload</h2>
 *
 * <p>The media object exists, it was scanned, and its {@code full} variant is servable. A missing
 * thumbnail is derived data, and {@code CLAUDE.md} rule 10 governs it: derived stores may fail,
 * never lie. So an unproducible derivative leaves the column null, the URL is not offered, and the
 * client renders the variant that does exist rather than a broken image.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DerivativeGenerator {

  /** REQ-MED-005's two sizes. The third, {@code full}, is produced during the upload. */
  private static final int THUMB_EDGE_PX = 200;

  private static final int PREVIEW_EDGE_PX = 1024;

  private final MediaObjectRepository objects;
  private final BlobStore blobs;
  private final ImageProcessor images;
  private final Clock clock;

  /**
   * Derives what is missing for one media object.
   *
   * @param tenantId the tenant, which establishes the context every query below needs
   * @param mediaObjectId the object
   */
  @Transactional
  public void derive(UUID tenantId, UUID mediaObjectId) {
    TenantContext.require();

    Optional<MediaObject> found = objects.findById(mediaObjectId);
    if (found.isEmpty()) {
      // Deleted between the publish and the delivery. Not an error: the outbox
      // guarantees the event arrives, not that its subject still exists.
      log.debug("Media object {} is gone; nothing to derive.", mediaObjectId);
      return;
    }

    MediaObject object = found.get();
    if (object.getDerivedAt() != null) {
      log.debug("Media object {} was already derived at {}.", mediaObjectId, object.getDerivedAt());
      return;
    }

    if (!object.getMediaType().startsWith("image/")) {
      // A PDF has no thumbnail at stage 0. Marked done all the same, or the
      // worker claims it again on every redelivery for the life of the object.
      object.recordDerivatives(null, null, Instant.now(clock));
      objects.save(object);
      return;
    }

    Path source = null;
    try {
      source = Files.createTempFile("homeinv-derive-", ".avif");
      try (InputStream stored = blobs.open(tenantId, object.getSha256())) {
        Files.copy(stored, source, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }

      String preview = produce(tenantId, source, PREVIEW_EDGE_PX);
      String thumb = produce(tenantId, source, THUMB_EDGE_PX);
      object.recordDerivatives(thumb, preview, Instant.now(clock));
      objects.save(object);

      log.info(
          "Derived thumb and preview for media object {} of tenant {}.", mediaObjectId, tenantId);

    } catch (IOException | RuntimeException failed) {
      // Logged and swallowed, deliberately. Rethrowing would return the message
      // to the broker and the same file would fail again on every redelivery,
      // filling the queue with work that cannot succeed. The columns stay null,
      // no URL is offered for what does not exist, and the `full` variant - the
      // one that matters - is unaffected.
      log.warn(
          "Could not derive variants for media object {} of tenant {}: {}",
          mediaObjectId,
          tenantId,
          failed.getMessage());
    } finally {
      deleteQuietly(source);
    }
  }

  /**
   * Derives one size and stores it under its own content address.
   *
   * @param tenantId the owning tenant
   * @param source the local copy of the {@code full} variant
   * @param maxEdge the longest edge of the result
   * @return the derivative's content address
   * @throws IOException when the derivative cannot be written or stored
   */
  private String produce(UUID tenantId, Path source, int maxEdge) throws IOException {
    Path target = Files.createTempFile("homeinv-derivative-", ".avif");
    try {
      images.derive(source, target, maxEdge, ImageProcessor.OutputFormat.AVIF);
      String sha256 = hashOf(target);
      try (InputStream bytes = Files.newInputStream(target)) {
        // Content-addressed in its own right, so two objects whose thumbnails
        // come out identical store one. Two photographs of the same white wall
        // have the same 200-pixel version, and that is not a rare case.
        blobs.store(tenantId, sha256, bytes);
      }
      return sha256;
    } finally {
      deleteQuietly(target);
    }
  }

  private static String hashOf(Path file) throws IOException {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
    try (InputStream bytes = Files.newInputStream(file)) {
      byte[] buffer = new byte[8192];
      int read;
      while ((read = bytes.read(buffer)) > 0) {
        digest.update(buffer, 0, read);
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static void deleteQuietly(Path file) {
    if (file == null) {
      return;
    }
    try {
      Files.deleteIfExists(file);
    } catch (IOException undeletable) {
      log.warn("A temporary derivative file could not be removed: {}", file);
    }
  }
}
