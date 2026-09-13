/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.identity.application;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Time-based one-time passwords, RFC 6238 (REQ-AUTH-002).
 *
 * <h2>Written out rather than pulled in</h2>
 *
 * <p>It is HMAC from the JDK, a truncation and a modulo — forty lines against a dependency whose
 * whole surface is this one function. The parameters are the ones every authenticator app assumes
 * and the only ones they all support: <b>SHA-1</b>, <b>six digits</b>, a <b>thirty-second</b> step.
 * SHA-1 is not a weakness here — HMAC-SHA-1 is unbroken, and the code lives for thirty seconds —
 * but it is also not a choice: an app that cannot read the QR code is a support case, and Google
 * Authenticator ignores the algorithm parameter altogether.
 *
 * <h2>Why a window, and why one step</h2>
 *
 * <p>A phone's clock drifts and a person types slowly, so the step before and after are accepted as
 * well: ninety seconds of validity in total. Wider would be a code an observer has longer to reuse,
 * and the replay guard on the credential row is what closes that gap anyway — a code from a step
 * already spent is refused whatever the window says.
 */
public final class TotpCodes {

  /** The step every authenticator app assumes. */
  public static final Duration STEP = Duration.ofSeconds(30);

  /** How many steps either side of the current one are accepted. */
  private static final int WINDOW = 1;

  /** Six digits, which is what the apps display. */
  private static final int DIGITS = 6;

  /** RFC 4226 §5.3's truncation and the modulus for six digits. */
  private static final int MODULUS = 1_000_000;

  /** 160 bits, the size HMAC-SHA-1's block structure is built for. */
  private static final int SECRET_BYTES = 20;

  private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

  private static final SecureRandom RANDOM = new SecureRandom();

  private TotpCodes() {}

  /**
   * A new shared secret.
   *
   * @return twenty random bytes, which the caller seals and shows once
   */
  public static byte[] newSecret() {
    byte[] secret = new byte[SECRET_BYTES];
    RANDOM.nextBytes(secret);
    return secret;
  }

  /**
   * Whether a code is valid for a secret at a given moment.
   *
   * <p>Compared in constant time. A comparison that returned early on the first wrong digit would
   * leak how much of a guess was right, which over enough attempts is the code.
   *
   * @param secret the shared secret
   * @param code what the person typed, digits only
   * @param now the moment to judge it at
   * @return the step the code belongs to when it is valid, or -1 when it is not. The step is what
   *     the caller needs for the replay guard: the same code in the same step is accepted once
   */
  public static long verify(byte[] secret, String code, Instant now) {
    if (code == null || code.length() != DIGITS) {
      return -1;
    }
    long current = now.getEpochSecond() / STEP.getSeconds();
    for (long step = current - WINDOW; step <= current + WINDOW; step++) {
      if (constantTimeEquals(generate(secret, step), code)) {
        return step;
      }
    }
    return -1;
  }

  /**
   * The step a moment falls in, for the replay guard.
   *
   * @param moment the instant
   * @return the RFC 6238 time-step counter
   */
  public static long stepOf(Instant moment) {
    return moment.getEpochSecond() / STEP.getSeconds();
  }

  /**
   * When a step began.
   *
   * <p>What the replay guard stores: the highest step already spent, rather than the wall-clock
   * moment it was spent at. The two differ for a code typed early — the window accepts the next
   * step as well — and storing the moment would let that code be replayed for the rest of its own
   * step.
   *
   * @param step the time-step counter
   * @return the instant the step began
   */
  public static Instant startOf(long step) {
    return Instant.ofEpochSecond(step * STEP.getSeconds());
  }

  /**
   * The code for one step.
   *
   * @param secret the shared secret
   * @param step the time-step counter
   * @return six digits, left-padded with zeroes
   */
  public static String generate(byte[] secret, long step) {
    try {
      Mac mac = Mac.getInstance("HmacSHA1");
      mac.init(new SecretKeySpec(secret, "HmacSHA1"));
      byte[] hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(step).array());

      // RFC 4226 §5.4: the low nibble of the last byte picks where to read from.
      int offset = hash[hash.length - 1] & 0x0f;
      int binary =
          ((hash[offset] & 0x7f) << 24)
              | ((hash[offset + 1] & 0xff) << 16)
              | ((hash[offset + 2] & 0xff) << 8)
              | (hash[offset + 3] & 0xff);
      return ("%0" + DIGITS + "d").formatted(binary % MODULUS);
    } catch (java.security.GeneralSecurityException impossible) {
      // HmacSHA1 is required of every JVM; if it is absent the process cannot
      // authenticate anybody and should say so rather than carry on.
      throw new IllegalStateException("HMAC-SHA-1 is unavailable in this runtime.", impossible);
    }
  }

  /**
   * The secret as an authenticator app expects to read it.
   *
   * @param secret the shared secret
   * @return RFC 4648 base32, unpadded and upper case
   */
  public static String base32(byte[] secret) {
    StringBuilder encoded = new StringBuilder();
    int buffer = 0;
    int bits = 0;
    for (byte b : secret) {
      buffer = (buffer << 8) | (b & 0xff);
      bits += 8;
      while (bits >= 5) {
        encoded.append(ALPHABET.charAt((buffer >> (bits - 5)) & 0x1f));
        bits -= 5;
      }
    }
    if (bits > 0) {
      encoded.append(ALPHABET.charAt((buffer << (5 - bits)) & 0x1f));
    }
    return encoded.toString();
  }

  /**
   * The {@code otpauth://} URI an authenticator app takes from a QR code.
   *
   * <p>The issuer appears twice — in the label and as a parameter — because the apps disagree about
   * which one they read, and one that reads neither shows an entry called by the address alone.
   *
   * @param issuer what to call this instance in the app's list
   * @param account the address the person signs in with
   * @param secret the shared secret
   * @return the URI, which is shown once and never stored
   */
  public static String provisioningUri(String issuer, String account, byte[] secret) {
    String label = encode(issuer) + ":" + encode(account);
    return "otpauth://totp/"
        + label
        + "?secret="
        + base32(secret)
        + "&issuer="
        + encode(issuer)
        + "&algorithm=SHA1&digits="
        + DIGITS
        + "&period="
        + STEP.getSeconds();
  }

  private static String encode(String value) {
    return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  /**
   * Compares two codes without returning early.
   *
   * @param expected the code this system generated
   * @param actual what was typed
   * @return whether they are equal
   */
  private static boolean constantTimeEquals(String expected, String actual) {
    byte[] a = expected.getBytes(StandardCharsets.UTF_8);
    byte[] b = actual.getBytes(StandardCharsets.UTF_8);
    if (a.length != b.length) {
      return false;
    }
    int difference = 0;
    for (int i = 0; i < a.length; i++) {
      difference |= a[i] ^ b[i];
    }
    return difference == 0;
  }
}
