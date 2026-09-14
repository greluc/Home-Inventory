/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.platform;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Encodes and verifies the opaque pagination cursor.
 *
 * <h2>Why it is signed</h2>
 *
 * <p>{@code REQ-SEC-106} requires a tampered cursor to be <em>rejected</em> rather than to silently
 * return wrong results, and that distinction is the whole point. A cursor is a position in a sorted
 * result set; an edited one does not fail, it quietly starts somewhere else — a client would page
 * past rows it should have seen and never learn that it had. The signature turns a silent wrong
 * answer into a loud refusal.
 *
 * <p>The cursor also carries a hash of the query it belongs to. {@code REQ-SRCH-009}'s acceptance is
 * "a cursor with changed filters is rejected": paging with a cursor from one search into a
 * different search is meaningless, and returning something anyway would be the same silent wrongness
 * one layer up.
 *
 * <h2>Why its own key</h2>
 *
 * <p>{@code HOMEINV_URL_SIGNING_KEY_FILE}, not the JWT key. They have different lifetimes and
 * different blast radii: rotating the session key must not invalidate every open result page, and a
 * cursor signature must never be usable as a session token or the other way round. Sharing one key
 * between two purposes means every rotation is a decision about both.
 *
 * <h2>Why there is no expiry</h2>
 *
 * <p>A cursor is not a capability: it grants nothing that the caller's own session does not already
 * grant, because the query it resumes is re-authorised on every page. An expiry would add a failure
 * mode — a user who leaves a list open over lunch — without removing one.
 */
@Component
@Slf4j
public class CursorCodec {

  private static final String ALGORITHM = "HmacSHA256";
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

  private final SecretKeySpec key;

  /**
   * Creates the codec.
   *
   * @param signingKey the URL and cursor signing key, read from the file named by
   *     {@code HOMEINV_URL_SIGNING_KEY_FILE}
   */
  public CursorCodec(UrlSigningKey signingKey) {
    this.key = new SecretKeySpec(signingKey.material(), ALGORITHM);
  }

  /**
   * Encodes a position into an opaque, signed cursor.
   *
   * @param position where the next page starts
   * @param queryFingerprint a stable hash of the query this cursor belongs to
   * @return the cursor, safe to put in a URL
   */
  public String encode(Position position, String queryFingerprint) {
    // The sort value is base64'd rather than written as it stands. It is a value
    // out of a row -- a name, a manufacturer -- and one containing the separator
    // would split the payload into the wrong pieces. Encoding it means the
    // separator can only ever come from here.
    String payload =
        position.createdAt().toString()
            + '|'
            + position.id()
            + '|'
            + ENCODER.encodeToString(
                (position.sortValue() == null ? "" : position.sortValue())
                    .getBytes(StandardCharsets.UTF_8))
            + '|'
            + queryFingerprint;
    return ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8))
        + '.'
        + ENCODER.encodeToString(sign(payload));
  }

  /**
   * Verifies a cursor and returns the position it names.
   *
   * @param cursor the cursor as the client sent it
   * @param queryFingerprint the fingerprint of the query being paged now
   * @return the position to resume from
   * @throws InvalidCursorException when the cursor is malformed, its signature does not match, or
   *     it belongs to a different query. The three are one exception on purpose: the caller can do
   *     nothing different about them, and distinguishing them tells an attacker which part of a
   *     forgery was wrong
   */
  public Position decode(String cursor, String queryFingerprint) {
    int separator = cursor == null ? -1 : cursor.indexOf('.');
    if (separator <= 0) {
      throw new InvalidCursorException();
    }

    String payload;
    byte[] presented;
    try {
      payload = new String(DECODER.decode(cursor.substring(0, separator)), StandardCharsets.UTF_8);
      presented = DECODER.decode(cursor.substring(separator + 1));
    } catch (IllegalArgumentException malformed) {
      throw new InvalidCursorException();
    }

    // Constant-time: a byte-by-byte comparison that returns early leaks, through
    // timing, how much of a forged signature was correct.
    if (!MessageDigest.isEqual(presented, sign(payload))) {
      log.info("Rejected a cursor whose signature did not match");
      throw new InvalidCursorException();
    }

    String[] parts = payload.split("\\|", 4);
    if (parts.length != 4 || !parts[3].equals(queryFingerprint)) {
      // Valid signature, wrong query: the client is paging one search with
      // another search's cursor, and any answer would be wrong (REQ-SRCH-009).
      throw new InvalidCursorException();
    }

    try {
      String sortValue = new String(DECODER.decode(parts[2]), StandardCharsets.UTF_8);
      return new Position(
          Instant.parse(parts[0]), UUID.fromString(parts[1]), sortValue.isEmpty() ? null : sortValue);
    } catch (RuntimeException unparseable) {
      throw new InvalidCursorException();
    }
  }

  private byte[] sign(String payload) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(key);
      return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
    } catch (java.security.GeneralSecurityException impossible) {
      // HmacSHA256 is required of every JVM, and the key was validated at startup.
      throw new IllegalStateException("Cursor signing is unavailable", impossible);
    }
  }

  /**
   * Where a page starts.
   *
   * <p>A timestamp and an id, because the default sort is {@code (created_at, id)}: the timestamp
   * alone is not unique, and two rows created in the same microsecond would make a page boundary
   * ambiguous — which is how a keyset pagination loses or repeats a row.
   *
   * <p>{@code sortValue} joins them when the caller asked for an order of its own
   * ({@code REQ-SRCH-004}). Keyset paging resumes at a <em>value</em>, so the value sorted by has to
   * travel in the cursor; {@code (created_at, id)} stays underneath it as the tie-break, because a
   * name or a manufacturer is no more unique than a timestamp. It is {@code null} for every listing
   * that pages by creation, which is all of them but one.
   *
   * <p>Held as text whatever the column's type. A cursor is opaque and is compared for equality
   * after the engine has parsed it back; carrying a typed value would mean a second place that
   * knows what each field holds, and {@code TypeRegistry.queryableFields} is the first.
   *
   * @param createdAt the creation instant of the last row of the previous page
   * @param id the id of that row, which breaks ties
   * @param sortValue the value the last row sorted by, or {@code null} when the order is the default
   */
  public record Position(Instant createdAt, UUID id, String sortValue) {

    /**
     * A position in the default order.
     *
     * @param createdAt the creation instant of the last row
     * @param id the id of that row
     * @return the position, carrying no sort value
     */
    public static Position of(Instant createdAt, UUID id) {
      return new Position(createdAt, id, null);
    }
  }
}
