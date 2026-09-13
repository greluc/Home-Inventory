/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import de.greluc.homeinv.idempotency.api.RequestKey;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads {@code Idempotency-Key} and pairs it with a hash of what was sent (REQ-API-005).
 *
 * <h2>The hash is of the parsed body, not of the bytes</h2>
 *
 * <p>A client that retries through a proxy, a different HTTP library or its own serialiser may send
 * the same request with different whitespace or a different key order. Hashing the bytes would call
 * that a different request and refuse it with {@code 409} — for a retry that is byte-for-byte
 * <em>meaningfully</em> identical, which is the one case this whole mechanism exists to serve.
 *
 * <p>So what is hashed is the request record re-serialised: the same object graph always produces
 * the same JSON, because a record's properties are written in declaration order.
 */
public final class IdempotencyKeys {

  private IdempotencyKeys() {}

  /** What the header may be, which is what the column accepts. */
  private static final int MAX_LENGTH = 255;

  /**
   * The key this request carries, with the hash of its body.
   *
   * <p>Absent when the header is: the key is optional, and a client that does not send one gets the
   * behaviour it always had. That is the contract — "every creating POST <em>accepts</em>
   * {@code Idempotency-Key}" — and it is also what keeps the header adoptable one client at a time.
   *
   * @param request the request, for the header
   * @param body the parsed request body, whatever record the endpoint takes
   * @param json the mapper, for the canonical form that is hashed
   * @return the key and the hash, or empty when no key was sent
   * @throws IllegalArgumentException when the header is present and not usable as a key
   */
  public static Optional<RequestKey> from(
      HttpServletRequest request, Object body, ObjectMapper json) {

    String header = request.getHeader("Idempotency-Key");
    if (header == null || header.isBlank()) {
      return Optional.empty();
    }
    String key = header.trim();
    if (key.length() > MAX_LENGTH) {
      throw new IllegalArgumentException(
          "Idempotency-Key is longer than " + MAX_LENGTH + " characters.");
    }
    for (int index = 0; index < key.length(); index++) {
      // Printable ASCII only. Not fussiness: the value is stored, compared and
      // logged, and a control character in any of those is somebody else's bug
      // report. A UUID — what a client should send — is well inside this.
      char character = key.charAt(index);
      if (character < 0x21 || character > 0x7e) {
        throw new IllegalArgumentException(
            "Idempotency-Key may only contain printable ASCII without spaces.");
      }
    }
    return Optional.of(new RequestKey(key, sha256(json.writeValueAsString(body))));
  }

  /**
   * The lower-case hex SHA-256 of a string.
   *
   * @param value the canonical JSON of the request
   * @return 64 hex characters
   */
  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      // Every JVM ships SHA-256; the checked exception is older than that promise.
      throw new IllegalStateException("SHA-256 is missing from this JVM", impossible);
    }
  }
}
