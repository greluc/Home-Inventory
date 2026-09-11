/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The key that signs media URLs and pagination cursors ({@code REQ-SEC-106}).
 *
 * <p>Deliberately not the JWT signing key. They have different lifetimes and different blast radii:
 * rotating the session key must not invalidate every open result page, and a cursor signature must
 * never be usable as a session token or the other way round. One key for two purposes makes every
 * rotation a decision about both.
 *
 * <p>Read from a <em>file</em>, not from an environment variable. An environment variable is
 * readable by anything that can list the process, appears in crash dumps and orchestration
 * descriptions, and survives in shell history; a file can be mounted read-only and given an owner.
 */
@Component
public class UrlSigningKey {

  /** Shorter than this and the HMAC is weaker than the hash it is built on. */
  private static final int MINIMUM_BYTES = 32;

  private final byte[] material;

  /**
   * Reads and validates the key.
   *
   * @param keyFile the path from {@code HOMEINV_URL_SIGNING_KEY_FILE}
   * @throws IllegalStateException when the file is missing, unreadable or too short. Startup fails
   *     rather than a default being generated: a generated key works perfectly until the second
   *     instance starts with a different one, and then cursors and media URLs fail for half of all
   *     requests in a way nobody traces back to here
   */
  public UrlSigningKey(@Value("${homeinv.security.url-signing-key-file:}") String keyFile) {
    if (keyFile == null || keyFile.isBlank()) {
      throw new IllegalStateException(
          "HOMEINV_URL_SIGNING_KEY_FILE is not set. It signs media URLs and pagination cursors "
              + "(REQ-SEC-106) and has no default, because a generated one differs per instance.");
    }

    byte[] read;
    try {
      read = Files.readAllBytes(Path.of(keyFile));
    } catch (IOException unreadable) {
      throw new IllegalStateException(
          "The URL signing key at " + keyFile + " could not be read", unreadable);
    }

    // Accept a base64 line as well as raw bytes: a key handed over as text is
    // what a secret manager usually produces, and silently hashing the literal
    // characters of a base64 string would work and be wrong.
    byte[] decoded = tryDecodeBase64(read);
    this.material = decoded != null ? decoded : read;

    if (material.length < MINIMUM_BYTES) {
      throw new IllegalStateException(
          "The URL signing key is %d bytes; at least %d are required."
              .formatted(material.length, MINIMUM_BYTES));
    }
  }

  /**
   * The key bytes.
   *
   * @return a copy, so no caller can alter the key held here
   */
  public byte[] material() {
    return material.clone();
  }

  private static byte[] tryDecodeBase64(byte[] raw) {
    try {
      return Base64.getMimeDecoder().decode(new String(raw, StandardCharsets.UTF_8).trim());
    } catch (IllegalArgumentException notBase64) {
      return null;
    }
  }
}
