/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import de.greluc.homeinv.identity.api.AuthenticatedUser;
import de.greluc.homeinv.media.api.MediaService;
import de.greluc.homeinv.media.api.MediaUrlSigner;
import de.greluc.homeinv.media.api.MediaView;
import jakarta.validation.constraints.Pattern;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Uploading files, listing them, and serving their bytes.
 *
 * <p>Two surfaces with different rules, which is why they are two paths rather than one:
 *
 * <ul>
 *   <li>{@code /api/v1/media/**} is the ordinary API — authenticated by session, CSRF-protected,
 *       returning JSON.
 *   <li>{@code /media/**} serves bytes, authorised by the <em>signature in the URL</em> and by
 *       nothing else. It has to be that way: a browser following an {@code <img src>} sends no
 *       session cookie to a different hostname, and {@code SameSite=Strict} would withhold it
 *       anyway ({@code REQ-MED-010}).
 * </ul>
 */
@RestController
@RequiredArgsConstructor
public class MediaController {

  private final MediaService media;
  private final MediaUrlSigner signer;

  /**
   * Uploads a file and attaches it to an item or a location.
   *
   * @param file the upload
   * @param targetKind {@code ITEM} or {@code LOCATION}
   * @param targetId what to attach it to
   * @param primary whether it becomes the image lists show
   * @param user the authenticated caller
   * @return the stored file with its signed URLs
   * @throws IOException when the upload cannot be read or stored
   */
  @PostMapping("/api/v1/media")
  public ResponseEntity<MediaView> upload(
      @RequestParam("file") MultipartFile file,
      @RequestParam @Pattern(regexp = "ITEM|LOCATION") String targetKind,
      @RequestParam UUID targetId,
      @RequestParam(defaultValue = "false") boolean primary,
      @AuthenticationPrincipal AuthenticatedUser user)
      throws IOException {

    try (InputStream content = file.getInputStream()) {
      MediaView view = media.upload(content, targetKind, targetId, primary, user.userId());
      return ResponseEntity.status(201).body(view);
    }
  }

  /**
   * The files attached to one thing.
   *
   * @param targetKind {@code ITEM} or {@code LOCATION}
   * @param targetId the target
   * @return the attachments, primary image first
   */
  @GetMapping("/api/v1/media")
  public List<MediaView> list(
      @RequestParam @Pattern(regexp = "ITEM|LOCATION") String targetKind,
      @RequestParam UUID targetId) {
    return media.attachmentsOf(targetKind, targetId);
  }

  /**
   * Detaches a file, removing the bytes when nothing references them.
   *
   * @param mediaObjectId the file
   * @param targetKind the target kind
   * @param targetId the target
   * @param user the authenticated caller
   * @return an empty {@code 204}
   */
  @DeleteMapping("/api/v1/media/{mediaObjectId}")
  public ResponseEntity<Void> detach(
      @PathVariable UUID mediaObjectId,
      @RequestParam @Pattern(regexp = "ITEM|LOCATION") String targetKind,
      @RequestParam UUID targetId,
      @AuthenticationPrincipal AuthenticatedUser user) {
    media.detach(mediaObjectId, targetKind, targetId, user.userId());
    return ResponseEntity.noContent().build();
  }

  /**
   * Serves the bytes of one variant, to whoever presents a valid signature.
   *
   * <p>No session and no tenant context: the signature carries the authorisation, and the tenant is
   * part of what it covers, so a URL cannot be replayed with a different tenant in the path.
   *
   * <p>Every response is {@code Content-Disposition: attachment} and
   * {@code X-Content-Type-Options: nosniff}. Even for an image: a file served inline from a hostname
   * runs in that hostname's origin if a browser can be persuaded to treat it as a document, and the
   * dedicated media hostname exists precisely so that the answer to "what if it is" is "nothing of
   * ours".
   *
   * @param tenantId the tenant, from the path and covered by the signature
   * @param sha256 the content address
   * @param variant {@code thumb}, {@code preview} or {@code full}
   * @param expires the expiry from the query
   * @param signature the signature from the query
   * @return the bytes, or {@code 404} when the signature does not verify
   * @throws IOException when the blob cannot be read
   */
  @GetMapping("/media/{tenantId}/{sha256}/{variant}")
  public ResponseEntity<InputStreamResource> serve(
      @PathVariable UUID tenantId,
      @PathVariable @Pattern(regexp = "[0-9a-f]{64}") String sha256,
      @PathVariable @Pattern(regexp = "thumb|preview|full") String variant,
      @RequestParam long expires,
      @RequestParam String signature)
      throws IOException {

    if (!signer.verify(tenantId, sha256, variant, expires, signature)) {
      // 404 and not 403: a 403 would confirm that this blob exists for this
      // tenant, which is exactly what an unsigned request must not learn.
      return ResponseEntity.notFound().build();
    }

    InputStream bytes = media.openVerified(tenantId, sha256);
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment")
        .header("X-Content-Type-Options", "nosniff")
        // Private: a shared cache must not keep a tenant's photo and hand it to
        // the next holder of the same URL after the signature has expired.
        .header(HttpHeaders.CACHE_CONTROL, "private, max-age=60")
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .body(new InputStreamResource(bytes));
  }
}
