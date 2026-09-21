/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.identity.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * The bearer values this block mints, and the form it stores them in.
 *
 * <p>A password reset token and a federated sign-in handle are the same thing twice: something a
 * stranger presents, generated here, stored only as a digest so that a database dump hands nobody a
 * live one (REQ-SEC-048). The PKCE verifier beside them is not a bearer value at all — it never
 * leaves this deployment — but it is minted from the same generator and its challenge is the same
 * digest in a different encoding.
 *
 * <p>Package-private and static: this is arithmetic, not a collaborator, and a bean would invite
 * somebody to replace it in a test.
 */
final class SingleUseTokens {

  /** 256 bits. Long enough that guessing is not a strategy, short enough for a URL. */
  private static final int TOKEN_BYTES = 32;

  /** Seeded by the platform; never a {@code Random}. */
  private static final SecureRandom RANDOM = new SecureRandom();

  private SingleUseTokens() {}

  /**
   * A fresh token.
   *
   * @return 256 bits of randomness, base64url without padding — 43 characters
   */
  static String mint() {
    byte[] bytes = new byte[TOKEN_BYTES];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /**
   * The SHA-256 of a token, in lower-case hexadecimal.
   *
   * @param token the token as presented
   * @return the hash a table stores, 64 characters
   */
  static String hash(String token) {
    return HexFormat.of().formatHex(digest(token));
  }

  /**
   * The PKCE challenge for a verifier, method {@code S256} (RFC 7636 §4.2).
   *
   * <p>The same digest as {@link #hash}, in base64url rather than hexadecimal, because that is the
   * encoding the protocol fixes. Two encodings of one digest is not duplication worth removing: a
   * challenge in hex is a challenge every provider rejects.
   *
   * @param verifier the verifier this deployment keeps
   * @return the challenge that travels to the provider
   */
  static String challengeFor(String verifier) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest(verifier));
  }

  private static byte[] digest(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException impossible) {
      // Every JVM ships SHA-256; the checked exception is a relic of an era when
      // that was not true. Failing loudly beats pretending to have hashed.
      throw new IllegalStateException("SHA-256 is not available in this JVM", impossible);
    }
  }
}
