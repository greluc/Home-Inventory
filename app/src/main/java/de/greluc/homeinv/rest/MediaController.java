/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import io.swagger.v3.oas.annotations.tags.Tag;
import de.greluc.homeinv.platform.Page;
import de.greluc.homeinv.authorization.api.Permission;
import de.greluc.homeinv.authorization.api.PublicEndpoint;
import de.greluc.homeinv.authorization.api.RequiresPermission;
import de.greluc.homeinv.identity.api.AuthenticatedUser;
import de.greluc.homeinv.media.api.MediaService;
import de.greluc.homeinv.media.api.MediaUrlSigner;
import de.greluc.homeinv.media.api.MediaView;
import de.greluc.homeinv.platform.NotFoundException;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
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
@Tag(name = "Media", description = "Photographs and documents, their derivatives and the signed URLs that serve them.")
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
   * @param role what the attachment is for — {@code PHOTO}, {@code RECEIPT}, {@code
   *     WARRANTY_PROOF} or {@code OTHER}. It is what lets the insurance report of REQ-LIFE-016
   *     attach the receipt rather than offering a list of files and leaving the reader to find it
   *     (REQ-LIFE-001 was amended for this on 2026-09-20)
   * @param user the authenticated caller
   * @return {@code 202} with the accepted file, its state, and a {@code Location} pointing at it
   * @throws IOException when the upload cannot be read or stored
   */
  @PostMapping(value = "/api/v1/media", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.MEDIA_CREATE)
  // 202 and not 201: the file has been accepted and stored, and it is not yet a
  // retrievable resource, because the scan runs in the worker (ADR-0024,
  // ADR-0054). The `Location` header names the resource that will say when it
  // is. Redundant at run time — the method sets the same status — and needed in
  // the document, which would otherwise describe the 200 springdoc infers from
  // the return type for an endpoint that never answers one.
  @ResponseStatus(HttpStatus.ACCEPTED)
  @CanFail({
    ProblemType.PAYLOAD_TOO_LARGE,
    ProblemType.VALIDATION_FAILED,
    ProblemType.UNSUPPORTED_MEDIA_TYPE
  })
  public ResponseEntity<MediaView> uploadMedia(
      @RequestParam("file") MultipartFile file,
      @RequestParam @Pattern(regexp = "ITEM|LOCATION") String targetKind,
      @RequestParam UUID targetId,
      @RequestParam(defaultValue = "false") boolean primary,
      @RequestParam(defaultValue = "PHOTO")
          @Pattern(regexp = "PHOTO|RECEIPT|WARRANTY_PROOF|OTHER")
          String role,
      @AuthenticationPrincipal AuthenticatedUser user)
      throws IOException {

    try (InputStream content = file.getInputStream()) {
      MediaView view =
          media.upload(content, targetKind, targetId, primary, role, user.userId());
      return ResponseEntity.accepted()
          .location(URI.create("/api/v1/media/" + view.id()))
          .body(view);
    }
  }

  /**
   * One file, by id — what an upload's {@code Location} header points at.
   *
   * <p>The resource a client polls after an upload. Its <em>status</em> carries the scan outcome so
   * that a client can branch without parsing a body: {@code 200} with signed URLs once the scan
   * cleared it, {@code 422} when the scanner refused it, {@code 503} while there is no verdict yet
   * (REQ-SEC-092, REQ-MED-013).
   *
   * @param mediaObjectId the file
   * @return the file with its signed URLs
   */
  @GetMapping(value = "/api/v1/media/{mediaObjectId}", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.MEDIA_READ)
  @CanFail({
    ProblemType.NOT_FOUND,
    ProblemType.MALWARE_DETECTED,
    ProblemType.SCAN_UNAVAILABLE
  })
  public MediaView findMedia(@PathVariable UUID mediaObjectId) {
    return media.findOne(mediaObjectId);
  }

  /**
   * One page of the files attached to one thing, oldest first.
   *
   * @param targetKind {@code ITEM} or {@code LOCATION}
   * @param targetId the target
   * @param cursor an opaque cursor from a previous page, or omitted for the first
   * @param limit how many at most; capped at 200 by the service
   * @return the page and a cursor for the next one
   */
  @GetMapping(value = "/api/v1/media", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.MEDIA_READ)
  @CanFail(ProblemType.MALFORMED_REQUEST)
  public Page<MediaView> listMedia(
      @RequestParam @Pattern(regexp = "ITEM|LOCATION") String targetKind,
      @RequestParam UUID targetId,
      @RequestParam(required = false) @Size(max = 500) String cursor,
      @RequestParam(required = false, defaultValue = "50") @Positive @Max(200) int limit) {
    return media.attachmentsOf(targetKind, targetId, cursor, limit);
  }

  /**
   * Detaches a file, removing the bytes when nothing references them.
   *
   * @param mediaObjectId the file
   * @param targetKind the target kind
   * @param targetId the target
   * @param user the authenticated caller
   */
  @DeleteMapping("/api/v1/media/{mediaObjectId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @RequiresPermission(Permission.MEDIA_DELETE)
  @CanFail(ProblemType.NOT_FOUND)
  public void detachMedia(
      @PathVariable UUID mediaObjectId,
      @RequestParam @Pattern(regexp = "ITEM|LOCATION") String targetKind,
      @RequestParam UUID targetId,
      @AuthenticationPrincipal AuthenticatedUser user) {
    media.detach(mediaObjectId, targetKind, targetId, user.userId());
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
   * @param issuedTo the user the link was signed for, from the query and covered by the signature
   * @param expires the expiry from the query
   * @param signature the signature from the query
   * @return the bytes, or {@code 404} when the signature does not verify
   * @throws IOException when the blob cannot be read
   */
  @GetMapping("/media/{tenantId}/{sha256}/{variant}")
  @CanFail(ProblemType.NOT_FOUND)
  @PublicEndpoint(
      reason =
          "Authorised by the signature in the URL and by nothing else (REQ-MED-010). A "
              + "browser following an <img src> to the media hostname sends no session "
              + "cookie, and SameSite=Strict withholds it even towards our own. The "
              + "signature is bound to the tenant, the object and an expiry, and an "
              + "invalid one is answered 404.")
  public ResponseEntity<InputStreamResource> serveMedia(
      @PathVariable UUID tenantId,
      @PathVariable @Pattern(regexp = "[0-9a-f]{64}") String sha256,
      @PathVariable @Pattern(regexp = "thumb|preview|full") String variant,
      @RequestParam("u") UUID issuedTo,
      @RequestParam long expires,
      @RequestParam String signature)
      throws IOException {

    if (!signer.verify(tenantId, issuedTo, sha256, variant, expires, signature)) {
      // 404 and not 403: a 403 would confirm that this blob exists for this
      // tenant, which is exactly what an unsigned request must not learn.
      //
      // Thrown rather than returned as an empty 404, so the answer is the same
      // RFC 9457 document every other failure in this API is (REQ-API-003). It
      // says nothing a valid signature would not have revealed.
      throw new NotFoundException("media", (UUID) null);
    }

    InputStream bytes = media.openVerified(tenantId, sha256);
    return ResponseEntity.ok()
        // Never inline, whatever the file is. A PDF displayed inline runs in a
        // viewer with the origin's privileges, and REQ-SEC-045 says never; the
        // same header makes the answer the same for every type rather than a
        // list of exceptions somebody has to keep right.
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment")
        .header("X-Content-Type-Options", "nosniff")
        // A sandbox with no allowances (REQ-SEC-043). Belt and braces next to
        // `attachment`: if a browser ever renders one of these anyway, it does so
        // in an opaque origin with no scripts, no forms and no popups.
        .header("Content-Security-Policy", "sandbox; default-src 'none'")
        // Foreign sites cannot embed a tenant's media. Same-site rather than
        // same-origin, because the media host is deliberately a different host
        // from the application's (MediaHostCheck, 12 §12.9).
        .header("Cross-Origin-Resource-Policy", "same-site")
        // Private: a shared cache must not keep a tenant's photo and hand it to
        // the next holder of the same URL after the signature has expired.
        .header(HttpHeaders.CACHE_CONTROL, "private, max-age=60")
        // Deliberately not the file's own type. `application/octet-stream` with
        // `nosniff` is the pair that stops a browser deciding for itself what a
        // response is, and nothing here needs a browser to render it.
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .body(new InputStreamResource(bytes));
  }
}
