/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import de.greluc.homeinv.authorization.api.Permission;
import de.greluc.homeinv.authorization.api.RequiresPermission;
import de.greluc.homeinv.identity.api.AuthenticatedUser;
import de.greluc.homeinv.media.api.ResumableUploads;
import de.greluc.homeinv.media.api.UploadSessionView;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * An upload that survives a broken connection (REQ-MED-008, ADR-0084).
 *
 * <h2>The protocol is tus 1.0.0</h2>
 *
 * <p>Four requests and no body format: create the upload, send the bytes in pieces, ask where it
 * got to, and — if it is abandoned — throw it away. The protocol is worth following rather than
 * inventing because the clients exist: a browser, a phone and a `curl` loop can all speak it
 * without agreeing with us about anything first.
 *
 * <p>Implemented here rather than taken from a library, for the reason this repository gives
 * elsewhere: the core protocol is four methods and a handful of headers, while every tus server
 * library brings a storage abstraction of its own that would have to be taught about the
 * {@code blobstore} service, per-tenant addressing and the scan — and a dependency appears in nine
 * licence notices (REQ-CON-013).
 *
 * <p>The extensions served are <b>creation</b>, <b>termination</b> and <b>expiration</b>.
 * Concatenation is not: it exists to upload one file over several parallel connections, and this
 * application's ceiling is 25 MB (REQ-SEC-037), where the second connection costs more than it
 * saves.
 *
 * <h2>What this adapter decides, which is nothing</h2>
 *
 * <p>Every rule — the ceiling, the offset, the expiry, what happens when the last byte arrives —
 * belongs to {@link ResumableUploads}. This translates headers into arguments and back, which is
 * what ADR-0010 says an adapter is for.
 */
@Tag(
    name = "Media",
    description = "Photographs and documents, their derivatives and the signed URLs that serve them.")
@RestController
@RequestMapping("/api/v1/media/uploads")
@RequiredArgsConstructor
public class MediaUploadController {

  /** The protocol version this speaks, echoed on every response the protocol touches. */
  static final String TUS_VERSION = "1.0.0";

  /** What the client sends on every request, and what it is told when it does not. */
  private static final String RESUMABLE = "Tus-Resumable";

  private final ResumableUploads uploads;

  /** REQ-SEC-037, announced rather than discovered: a client can refuse before sending. */
  @Value("${homeinv.media.max-bytes:26214400}")
  private long maxBytes;

  /**
   * What this server supports, asked before anything is created.
   *
   * <p>Authenticated like everything else under {@code /api/v1}: it says what the protocol can do
   * here, which is of no use to somebody who may not upload.
   *
   * @return {@code 204} with the version, the ceiling and the extensions
   */
  @RequestMapping(method = RequestMethod.OPTIONS)
  @RequiresPermission(Permission.MEDIA_CREATE)
  public ResponseEntity<Void> options() {
    return ResponseEntity.noContent()
        .header(RESUMABLE, TUS_VERSION)
        .header("Tus-Version", TUS_VERSION)
        .header("Tus-Max-Size", Long.toString(maxBytes))
        .header("Tus-Extension", "creation,termination,expiration")
        .build();
  }

  /**
   * Begins an upload and answers where to send the bytes.
   *
   * <p>The length is declared here and checked <b>before a byte arrives</b>, which is what
   * REQ-SEC-037 asks for and what the one-shot path can only approximate by cutting the stream off
   * mid-read.
   *
   * @param length how many bytes will follow, from {@code Upload-Length}
   * @param metadata what the file will be attached to, from {@code Upload-Metadata}
   * @param resumable the protocol version the client speaks
   * @param user the authenticated caller
   * @return {@code 201} with the upload's URL in {@code Location}
   */
  @PostMapping
  @RequiresPermission(Permission.MEDIA_CREATE)
  @CanFail({
    ProblemType.PAYLOAD_TOO_LARGE,
    ProblemType.MALFORMED_REQUEST,
    ProblemType.PRECONDITION_FAILED
  })
  public ResponseEntity<Void> create(
      @RequestHeader(value = "Upload-Length", required = false) Long length,
      @RequestHeader(value = "Upload-Metadata", required = false) String metadata,
      @RequestHeader(value = RESUMABLE, required = false) String resumable,
      @AuthenticationPrincipal AuthenticatedUser user) {

    requireProtocol(resumable);
    if (length == null || length <= 0) {
      throw new TusRequestException(
          ProblemType.MALFORMED_REQUEST, "Upload-Length is required and must be positive");
    }
    Map<String, String> declared = metadata(metadata);
    UploadSessionView upload =
        uploads.begin(
            required(declared, "targetKind"),
            UUID.fromString(required(declared, "targetId")),
            Boolean.parseBoolean(declared.getOrDefault("primary", "false")),
            declared.getOrDefault("role", "PHOTO"),
            length,
            user.userId());

    return ResponseEntity.created(URI.create("/api/v1/media/uploads/" + upload.id()))
        .header(RESUMABLE, TUS_VERSION)
        .header("Upload-Expires", httpDate(upload))
        .build();
  }

  /**
   * Where an upload stands.
   *
   * <p>The one request a client makes after a connection dropped, and the reason this feature
   * exists: the offset comes from the store that holds the bytes, so the answer is what actually
   * arrived rather than what either side believed.
   *
   * @param uploadId the upload
   * @param resumable the protocol version the client speaks
   * @return {@code 200} with the offset and the declared length
   */
  @RequestMapping(value = "/{uploadId}", method = RequestMethod.HEAD)
  @RequiresPermission(Permission.MEDIA_CREATE)
  public ResponseEntity<Void> status(
      @PathVariable UUID uploadId,
      @RequestHeader(value = RESUMABLE, required = false) String resumable) {

    requireProtocol(resumable);
    UploadSessionView upload = uploads.status(uploadId);
    ResponseEntity.BodyBuilder answer =
        offsetResponse(upload, HttpStatus.OK)
            // A cached offset is a client resuming from a place that has moved.
            // tus names this header for exactly this response.
            .header("Cache-Control", "no-store")
            .header("Upload-Length", Long.toString(upload.declaredLength()))
            .header("Upload-Expires", httpDate(upload));
    if (upload.isComplete()) {
      // The upload finished and the client is asking because it never saw the
      // response that said so — the last failure a resumable upload can have.
      // Naming the file here is what makes that recoverable instead of a file
      // stored twice.
      answer.location(URI.create("/api/v1/media/" + upload.mediaObjectId()));
    }
    return answer.build();
  }

  /**
   * Sends the next piece, and finishes the upload when it was the last one.
   *
   * <p>On completion the response carries {@code Location}, pointing at the file that was created —
   * the same resource the one-shot upload's {@code 202} names. tus says nothing about this because
   * tus does not know what the bytes become; a client that lost the response asks again and is told
   * the same thing, because the upload remembers what it produced.
   *
   * @param uploadId the upload
   * @param offset where these bytes go, from {@code Upload-Offset}
   * @param contentType what tus requires on this request and nothing else
   * @param resumable the protocol version the client speaks
   * @param request the request, whose body is the bytes
   * @param user the authenticated caller
   * @return {@code 204} with the new offset
   * @throws IOException when the bytes cannot be read or staged
   */
  // NOT `consumes`, and that was the first spelling. Content negotiation happens
  // while the handler is being chosen, which is BEFORE the interceptor that
  // checks the permission — so a caller who may not upload at all was told 415
  // about their content type rather than 403 about their role, and
  // `EndpointNegativeCoverageIT` refused it. Authorisation goes first
  // (REQ-SEC-026); the type is checked in the method, where it answers the same
  // 415 the protocol asks for.
  @PatchMapping("/{uploadId}")
  @RequiresPermission(Permission.MEDIA_CREATE)
  @CanFail({
    ProblemType.PAYLOAD_TOO_LARGE,
    ProblemType.UNSUPPORTED_MEDIA_TYPE,
    ProblemType.UPLOAD_OFFSET_MISMATCH,
    ProblemType.UPLOAD_IN_PROGRESS,
    ProblemType.MALFORMED_REQUEST,
    ProblemType.PRECONDITION_FAILED
  })
  public ResponseEntity<Void> append(
      @PathVariable UUID uploadId,
      @RequestHeader(value = "Upload-Offset", required = false) Long offset,
      @RequestHeader(value = "Content-Type", required = false) String contentType,
      @RequestHeader(value = RESUMABLE, required = false) String resumable,
      HttpServletRequest request,
      @AuthenticationPrincipal AuthenticatedUser user)
      throws IOException {

    requireProtocol(resumable);
    if (contentType == null || !contentType.startsWith("application/offset+octet-stream")) {
      // tus names this content type. It is not pedantry: a PATCH with any other
      // one is a client that thinks it is doing something else, and appending
      // its body would be the wrong bytes at the right offset.
      throw new TusRequestException(
          ProblemType.UNSUPPORTED_MEDIA_TYPE,
          "A PATCH to an upload carries application/offset+octet-stream");
    }
    if (offset == null || offset < 0) {
      throw new TusRequestException(
          ProblemType.MALFORMED_REQUEST, "Upload-Offset is required and must not be negative");
    }

    UploadSessionView upload;
    try (InputStream body = request.getInputStream()) {
      upload = uploads.append(uploadId, offset, body, user.userId());
    }

    ResponseEntity.BodyBuilder answer = offsetResponse(upload, HttpStatus.NO_CONTENT);
    if (upload.isComplete()) {
      answer.location(URI.create("/api/v1/media/" + upload.mediaObjectId()));
    }
    return answer.build();
  }

  /**
   * Abandons an upload and discards what arrived.
   *
   * @param uploadId the upload
   * @param resumable the protocol version the client speaks
   * @return {@code 204}
   */
  @DeleteMapping("/{uploadId}")
  @RequiresPermission(Permission.MEDIA_CREATE)
  public ResponseEntity<Void> abort(
      @PathVariable UUID uploadId,
      @RequestHeader(value = RESUMABLE, required = false) String resumable) {

    requireProtocol(resumable);
    uploads.abort(uploadId);
    return ResponseEntity.noContent().header(RESUMABLE, TUS_VERSION).build();
  }

  /**
   * The response shape both the offset requests share.
   *
   * @param upload where the upload stands
   * @param status what to answer with
   * @return a builder carrying the protocol headers
   */
  private static ResponseEntity.BodyBuilder offsetResponse(
      UploadSessionView upload, HttpStatus status) {
    return ResponseEntity.status(status)
        .header(RESUMABLE, TUS_VERSION)
        .header("Upload-Offset", Long.toString(upload.offset()));
  }

  /**
   * Refuses a client that did not name the protocol.
   *
   * <p>tus asks for {@code 412} here, and the point is forward compatibility: a client written
   * against a later version must find out from the status rather than from a half-completed upload.
   *
   * @param resumable what the client sent
   */
  private static void requireProtocol(String resumable) {
    if (!TUS_VERSION.equals(resumable)) {
      throw new TusRequestException(
          ProblemType.PRECONDITION_FAILED,
          "This endpoint speaks tus " + TUS_VERSION + "; send it in Tus-Resumable");
    }
  }

  /**
   * Reads tus's {@code Upload-Metadata}: comma-separated {@code key base64value} pairs.
   *
   * <p>A key with no value is permitted by the protocol and carries an empty string, which is why
   * the split is bounded at two rather than asserted to produce two.
   *
   * @param header what the client sent, or null
   * @return the pairs, decoded
   */
  private static Map<String, String> metadata(String header) {
    Map<String, String> pairs = new HashMap<>();
    if (header == null || header.isBlank()) {
      return pairs;
    }
    for (String entry : header.split(",")) {
      String[] parts = entry.trim().split(" ", 2);
      if (parts[0].isEmpty()) {
        continue;
      }
      String value =
          parts.length < 2
              ? ""
              : new String(Base64.getDecoder().decode(parts[1]), StandardCharsets.UTF_8);
      pairs.put(parts[0], value);
    }
    return pairs;
  }

  /**
   * One metadata value the upload cannot be created without.
   *
   * @param declared what arrived
   * @param key which value
   * @return the value
   */
  private static String required(Map<String, String> declared, String key) {
    String value = declared.get(key);
    if (value == null || value.isBlank()) {
      throw new TusRequestException(
          ProblemType.MALFORMED_REQUEST, "Upload-Metadata must carry " + key);
    }
    return value;
  }

  /**
   * The expiry, in the one format the protocol names.
   *
   * @param upload the upload
   * @return an RFC 7231 date
   */
  private static String httpDate(UploadSessionView upload) {
    return DateTimeFormatter.RFC_1123_DATE_TIME.format(
        upload.expiresAt().atZone(ZoneOffset.UTC));
  }
}
