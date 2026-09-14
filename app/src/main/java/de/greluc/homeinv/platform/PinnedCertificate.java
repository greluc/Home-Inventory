/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.platform;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.Locale;
import javax.net.ssl.X509TrustManager;

/**
 * Accepts exactly one server certificate, by its SHA-256 fingerprint (REQ-SEC-056, ADR-0044).
 *
 * <p>It checks the leaf and nothing else — not the chain, not the hostname, not the validity dates.
 * That is deliberate and is what pinning means: the question is "is this the certificate we were
 * told to expect", and a chain that validates to a CA several services share does not answer it.
 * The deployment's CA signs {@code api}, {@code worker}, {@code blobstore}, {@code opensearch} and
 * in stage 1 every plugin, so trusting it alone would let any of them answer as any other.
 * {@code internal} is a network and not a trust boundary.
 *
 * <p>Expiry is handled by reissuing the certificate and updating the pin together, which is one
 * operation rather than two that can disagree.
 *
 * <p>In {@code platform} because two clients need it — the gRPC channel to {@code blobstore} and
 * the HTTP client to OpenSearch — and a second copy of a trust decision is how the two come to
 * disagree about what they trust.
 *
 * @param peer what the connection is to, named in the refusal so an operator knows which pin to
 *     look at
 * @param variable the environment variable holding the expected fingerprint, likewise
 * @param expected the fingerprint, already normalised by {@link #normalise}
 */
public record PinnedCertificate(String peer, String variable, String expected)
    implements X509TrustManager {

  /**
   * Strips the punctuation an operator may have pasted and folds the case.
   *
   * <p>A fingerprint is copied out of {@code openssl x509 -fingerprint}, which prints it with
   * colons, or out of a certificate viewer, which may not. Both are the same fingerprint and a
   * comparison that said otherwise would be a configuration error nobody could see.
   *
   * @param fingerprint as it was configured, possibly {@code null}
   * @return the hex digits alone, lower-case; empty when nothing was configured
   */
  public static String normalise(String fingerprint) {
    return fingerprint == null
        ? ""
        : fingerprint.replace(":", "").trim().toLowerCase(Locale.ROOT);
  }

  /**
   * Refuses to verify a client, because this is one.
   *
   * @param chain ignored
   * @param authType ignored
   * @throws CertificateException always
   */
  @Override
  public void checkClientTrusted(X509Certificate[] chain, String authType)
      throws CertificateException {
    throw new CertificateException("This trust manager verifies servers only");
  }

  /**
   * Accepts the connection only when the leaf certificate is the pinned one.
   *
   * @param chain what the peer presented
   * @param authType ignored
   * @throws CertificateException when nothing was presented, or it is not the expected certificate
   */
  @Override
  public void checkServerTrusted(X509Certificate[] chain, String authType)
      throws CertificateException {
    if (chain == null || chain.length == 0) {
      throw new CertificateException(peer + " presented no certificate");
    }
    String presented = fingerprintOf(chain[0]);
    // Constant-time, like every other comparison of a secret-shaped value here.
    // A fingerprint is not a secret, but a comparison that leaks its prefix is a
    // habit worth not having.
    if (!MessageDigest.isEqual(
        presented.getBytes(StandardCharsets.US_ASCII),
        expected.getBytes(StandardCharsets.US_ASCII))) {
      throw new CertificateException(
          peer + "'s certificate does not match " + variable);
    }
  }

  /**
   * Accepts no issuer, because no issuer decides anything here.
   *
   * @return an empty array
   */
  @Override
  public X509Certificate[] getAcceptedIssuers() {
    return new X509Certificate[0];
  }

  /**
   * The SHA-256 fingerprint of one certificate, as lower-case hex.
   *
   * @param certificate what was presented
   * @return the fingerprint
   * @throws CertificateException when the certificate cannot be encoded or hashed
   */
  private static String fingerprintOf(X509Certificate certificate) throws CertificateException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(certificate.getEncoded()));
    } catch (NoSuchAlgorithmException | CertificateEncodingException impossible) {
      throw new CertificateException("The presented certificate could not be hashed", impossible);
    }
  }
}
