/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.media.infrastructure;

import io.grpc.ChannelCredentials;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.TlsChannelCredentials;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import javax.net.ssl.X509TrustManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Builds the mutually authenticated, certificate-pinned channel to {@code blobstore}.
 *
 * <h2>Pinned, not merely signed</h2>
 *
 * <p>The deployment's CA signs several identities — {@code api}, {@code worker}, {@code blobstore},
 * and in stage 1 every plugin. Trusting the CA alone would mean any of them could answer as the
 * blob store: a compromised plugin holding a valid client certificate could stand up a service on
 * the segment and receive every tenant's media. Pinning the one certificate that may answer makes
 * that a failed handshake rather than a silent redirection, and it is the same mechanism a plugin
 * registration uses ({@code REQ-SEC-056}, ADR-0044).
 *
 * <p>The fingerprint comes from {@code HOMEINV_BLOBSTORE_FINGERPRINT} and has no default. A
 * generated one would be a pin that matches whatever answers first.
 */
@Slf4j
@Component
public class BlobStoreChannelFactory {

  private final String endpoint;
  private final String expectedFingerprint;
  private final Path identityFile;
  private final String authority;

  /**
   * Reads the configuration.
   *
   * @param endpoint {@code host:port} of the blob store, from {@code HOMEINV_BLOBSTORE_ENDPOINT}
   * @param fingerprint the SHA-256 fingerprint of its certificate, lowercase hex without separators
   * @param identityFile this service's own PEM bundle, from {@code HOMEINV_MTLS_CORE_FILE}
   * @param authority the name to verify the certificate against, when it differs from the
   *     endpoint's host. Empty in the deployment, where the two are the same word
   */
  // Annotated because this class has two public constructors and Spring picks
  // none of them by itself. Constructor injection with one constructor needs no
  // annotation (CLAUDE.md); with two, saying which is the point.
  @Autowired
  public BlobStoreChannelFactory(
      @Value("${HOMEINV_BLOBSTORE_ENDPOINT:blobstore:8100}") String endpoint,
      @Value("${HOMEINV_BLOBSTORE_FINGERPRINT:}") String fingerprint,
      @Value("${HOMEINV_MTLS_CORE_FILE:}") String identityFile,
      @Value("${HOMEINV_BLOBSTORE_AUTHORITY:}") String authority) {
    this.endpoint = endpoint;
    this.expectedFingerprint = normalise(fingerprint);
    this.identityFile = identityFile == null || identityFile.isBlank() ? null : Path.of(identityFile);
    this.authority = authority == null ? "" : authority.trim();
  }

  /**
   * The three-argument form, for callers that reach the store under the name its certificate
   * carries.
   *
   * @param endpoint {@code host:port} of the blob store
   * @param fingerprint the SHA-256 fingerprint of its certificate
   * @param identityFile this service's own PEM bundle
   */
  public BlobStoreChannelFactory(String endpoint, String fingerprint, String identityFile) {
    this(endpoint, fingerprint, identityFile, "");
  }

  /**
   * Opens the channel.
   *
   * @return a channel that speaks only to the pinned certificate
   * @throws IllegalStateException when the pin or the identity is missing or unusable. Startup then
   *     fails, which is the intended behaviour: a client that fell back to an unauthenticated
   *     connection would work perfectly and would be talking to whatever answered
   */
  public ManagedChannel open() {
    if (expectedFingerprint.isEmpty()) {
      throw new IllegalStateException(
          "HOMEINV_BLOBSTORE_FINGERPRINT is not set. It pins the one certificate that may answer "
              + "as the blob store; without it any holder of a deployment certificate could "
              + "(REQ-SEC-056, ADR-0044).");
    }
    if (identityFile == null) {
      throw new IllegalStateException(
          "HOMEINV_MTLS_CORE_FILE is not set. The blob store authenticates its callers and this "
              + "service has no identity to present.");
    }

    byte[] bundle;
    try {
      bundle = Files.readAllBytes(identityFile);
    } catch (IOException unreadable) {
      throw new IllegalStateException(
          "The mTLS identity at " + identityFile + " could not be read", unreadable);
    }

    try {
      ChannelCredentials credentials =
          TlsChannelCredentials.newBuilder()
              .keyManager(new ByteArrayInputStream(bundle), new ByteArrayInputStream(bundle))
              .trustManager(new PinnedTrustManager(expectedFingerprint))
              .build();
      var builder = Grpc.newChannelBuilder(endpoint, credentials);
      if (!authority.isEmpty()) {
        // gRPC verifies the certificate's subject alternative name against the
        // authority it is connecting to, and the pin does not replace that check
        // — the two answer different questions. In the deployment the endpoint IS
        // `blobstore`, so the names agree and this is empty. It exists for the
        // case where they cannot: a port-forwarded address, or a test reaching
        // the container on `localhost`.
        builder = builder.overrideAuthority(authority);
      }
      return builder.build();
    } catch (IOException | RuntimeException unusable) {
      throw new IllegalStateException(
          "The channel to the blob store at " + endpoint + " could not be built", unusable);
    }
  }

  private static String normalise(String fingerprint) {
    return fingerprint == null
        ? ""
        : fingerprint.replace(":", "").trim().toLowerCase(Locale.ROOT);
  }

  /**
   * Accepts exactly one server certificate, by its SHA-256 fingerprint.
   *
   * <p>It checks the leaf and nothing else — not the chain, not the hostname, not the validity
   * dates. That is deliberate and is what pinning means: the question is "is this the certificate we
   * were told to expect", and a chain that validates to a CA several services share does not answer
   * it. Expiry is handled by reissuing the certificate and updating the pin together, which is one
   * operation rather than two that can disagree.
   */
  private record PinnedTrustManager(String expected) implements X509TrustManager {

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType)
        throws CertificateException {
      // This is a client. It is never asked to verify a client.
      throw new CertificateException("This trust manager verifies servers only");
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType)
        throws CertificateException {
      if (chain == null || chain.length == 0) {
        throw new CertificateException("The blob store presented no certificate");
      }
      String presented = fingerprintOf(chain[0]);
      if (!MessageDigest.isEqual(
          presented.getBytes(StandardCharsets.US_ASCII),
          expected.getBytes(StandardCharsets.US_ASCII))) {
        throw new CertificateException(
            "The blob store's certificate does not match HOMEINV_BLOBSTORE_FINGERPRINT");
      }
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
      // No issuer is accepted, because no issuer decides anything here.
      return new X509Certificate[0];
    }

    private static String fingerprintOf(X509Certificate certificate) throws CertificateException {
      try {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(certificate.getEncoded()));
      } catch (NoSuchAlgorithmException | CertificateEncodingException impossible) {
        throw new CertificateException("The presented certificate could not be hashed", impossible);
      }
    }
  }

  /**
   * Parses every certificate in a PEM bundle.
   *
   * @param pem the bundle
   * @return the certificates, in the order they appear
   * @throws CertificateException when one of them cannot be read
   */
  static List<X509Certificate> certificatesIn(byte[] pem) throws CertificateException {
    CertificateFactory factory = CertificateFactory.getInstance("X.509");
    List<X509Certificate> certificates = new ArrayList<>();
    try (ByteArrayInputStream stream = new ByteArrayInputStream(pem)) {
      while (stream.available() > 0) {
        certificates.add((X509Certificate) factory.generateCertificate(stream));
      }
    } catch (IOException | RuntimeException end) {
      // The factory throws when the remaining bytes are not a certificate, which
      // is how a bundle ending in a key or in whitespace finishes.
    }
    return certificates;
  }
}
