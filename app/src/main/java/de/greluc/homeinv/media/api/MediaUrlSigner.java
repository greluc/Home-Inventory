/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.media.api;

import de.greluc.homeinv.platform.UrlSigningKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Signs and verifies the short-lived media URLs ({@code REQ-MED-010}).
 *
 * <h2>What the signature is for</h2>
 *
 * <p>Media is not served through the API. It is served from a hostname of its own, to a browser
 * that follows an {@code <img src>} without sending the session cookie — {@code SameSite=Strict}
 * sees to that, and a dedicated hostname would not receive a {@code __Host-} cookie anyway. So the
 * URL itself has to carry the authorisation, and a signature with an expiry is what makes a link
 * that leaks into a log or a referrer header stop working.
 *
 * <h2>Its own key, and why that is not pedantry</h2>
 *
 * <p>{@code HOMEINV_URL_SIGNING_KEY_FILE}, never the session key ({@code REQ-SEC-106}). The
 * acceptance criterion is explicit: a media URL signed with the JWT key is rejected and the reverse
 * too, and rotating one does not invalidate the other. One key for both would make every session-key
 * rotation break every outstanding image link, which is the kind of coupling that gets a rotation
 * postponed.
 *
 * <h2>What the signature covers</h2>
 *
 * <p>Tenant, blob, variant and expiry — all four. Signing only the blob would let an expiry be
 * edited; omitting the tenant would let a URL signed for one tenant be replayed with another
 * tenant's identifier in the path, which the signature would then vouch for.
 */
@Component
@Slf4j
public class MediaUrlSigner {

  private static final String ALGORITHM = "HmacSHA256";
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

  private final SecretKeySpec key;
  private final Duration validity;
  private final Clock clock;

  /**
   * Creates the signer.
   *
   * @param signingKey the URL signing key, separate from the session key
   * @param validitySeconds how long a link lasts; short, because a link is generated per view and a
   *     long-lived one is a link that outlives the permission it was issued under
   * @param clock the clock, so an expiry test needs no sleeping
   */
  public MediaUrlSigner(
      UrlSigningKey signingKey,
      @Value("${homeinv.media.url-validity-seconds:300}") long validitySeconds,
      Clock clock) {
    this.key = new SecretKeySpec(signingKey.material(), ALGORITHM);
    this.validity = Duration.ofSeconds(validitySeconds);
    this.clock = clock;
  }

  /**
   * Signs a link to one variant of one blob.
   *
   * @param tenantId the owning tenant
   * @param sha256 the content address
   * @param variant {@code thumb}, {@code preview} or {@code full}
   * @return the query string to append, carrying the expiry and the signature
   */
  public String sign(UUID tenantId, String sha256, String variant) {
    long expiresAt = Instant.now(clock).plus(validity).getEpochSecond();
    String payload = payload(tenantId, sha256, variant, expiresAt);
    return "?expires=" + expiresAt + "&signature=" + ENCODER.encodeToString(mac(payload));
  }

  /**
   * Verifies a presented link.
   *
   * @param tenantId the tenant from the path
   * @param sha256 the blob from the path
   * @param variant the variant from the path
   * @param expiresAt the expiry from the query
   * @param signature the signature from the query
   * @return {@code true} when the signature matches and the link has not expired
   */
  public boolean verify(
      UUID tenantId, String sha256, String variant, long expiresAt, String signature) {
    byte[] presented;
    try {
      presented = DECODER.decode(signature);
    } catch (IllegalArgumentException | NullPointerException malformed) {
      return false;
    }

    // The signature is checked before the expiry, and both are checked. Checking
    // the expiry first would answer faster for an expired link than for a forged
    // one, which says which of the two a probe got wrong.
    boolean signatureMatches =
        MessageDigest.isEqual(presented, mac(payload(tenantId, sha256, variant, expiresAt)));
    boolean notExpired = Instant.now(clock).getEpochSecond() <= expiresAt;

    if (!signatureMatches) {
      log.info("Rejected a media URL whose signature did not match");
    }
    return signatureMatches && notExpired;
  }

  /**
   * The string the signature covers.
   *
   * <p>Separated by a character that cannot occur in any component, so two different tuples cannot
   * produce the same payload — a tenant ending in one digit and a hash beginning with another would
   * otherwise be indistinguishable from the reverse.
   *
   * @param tenantId the tenant
   * @param sha256 the blob
   * @param variant the variant
   * @param expiresAt the expiry, in epoch seconds
   * @return the payload
   */
  private static String payload(UUID tenantId, String sha256, String variant, long expiresAt) {
    return tenantId + "\n" + sha256 + "\n" + variant + "\n" + expiresAt;
  }

  private byte[] mac(String payload) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(key);
      return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
    } catch (java.security.GeneralSecurityException impossible) {
      throw new IllegalStateException("Media URL signing is unavailable", impossible);
    }
  }
}
