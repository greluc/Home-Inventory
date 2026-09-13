/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import jakarta.servlet.http.HttpServletRequest;
import java.util.OptionalLong;

/**
 * {@code ETag} and {@code If-Match}, in the one shape this API uses (REQ-API-004).
 *
 * <h2>The tag is the version</h2>
 *
 * <p>{@code ETag: "7"} — the resource's optimistic-lock version, quoted, and nothing else. Not a
 * hash of the body, for a reason specific to this system: a response body is <em>redacted per
 * caller</em> (REQ-TEN-008), so two people with different field permissions would compute two tags
 * for the same unchanged row and each would think the other had changed it. The version is a
 * property of the row, which is what a precondition is about.
 *
 * <p>Strong, therefore, and not weak: it identifies the exact state a write will be checked
 * against, which is the one thing a weak tag may not promise.
 *
 * <h2>Where the two halves live</h2>
 *
 * <p>Demanding the header is a protocol rule and is here. <b>Comparing</b> it is a domain rule and
 * is not: the value travels into the service, which holds it against the row inside the same
 * transaction as the write. An adapter that compared it would be deciding something, and the window
 * between its read and the write would be exactly the race the header exists to prevent.
 */
public final class EntityTags {

  private EntityTags() {}

  /**
   * The tag for a version.
   *
   * @param version the resource's version
   * @return the quoted tag, as {@code ResponseEntity.eTag} wants it
   */
  public static String of(long version) {
    return "\"" + version + "\"";
  }

  /**
   * The version a mutating request says it acted on.
   *
   * <p>{@code If-Match: *} is <b>refused</b>, and that is a deliberate departure from what RFC 9110
   * gives the wildcard. There it asserts only that the resource exists, which for an update means
   * "overwrite whatever is there" — and REQ-API-004's acceptance is "blind overwriting is
   * impossible", which a spelling for overwriting blindly would make false. {@code 428} is the
   * right answer to it rather than {@code 412}: the server is saying which precondition it wants,
   * which is the status's whole purpose (RFC 6585).
   *
   * @param request the request
   * @return the version the caller acted on; never empty, because every path here either returns
   *     one or throws
   * @throws PreconditionRequiredException when the header is absent, empty or {@code *}
   * @throws PreconditionMalformedException when it is there and is not a version this API issued
   */
  public static OptionalLong required(HttpServletRequest request) {
    String header = request.getHeader("If-Match");
    if (header == null || header.isBlank() || "*".equals(header.trim())) {
      throw new PreconditionRequiredException();
    }
    String value = header.trim();
    // A weak tag is never issued here, so one arriving back cannot match anything
    // this API knows; it is refused rather than silently unwrapped.
    if (value.length() < 3 || value.charAt(0) != '"' || value.charAt(value.length() - 1) != '"') {
      throw new PreconditionMalformedException();
    }
    try {
      return OptionalLong.of(Long.parseLong(value.substring(1, value.length() - 1)));
    } catch (NumberFormatException notAVersion) {
      throw new PreconditionMalformedException();
    }
  }
}
