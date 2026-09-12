/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import java.io.IOException;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * A throwaway certificate authority, made fresh for each test run.
 *
 * <h2>Why generated and not checked in</h2>
 *
 * <p>A private key in the repository is a private key in every clone of it, and {@code gitleaks}
 * would be right to fail the build over one. Generating in memory and writing to a temporary
 * directory that the run deletes means no key outlives the JVM that made it (CLAUDE.md, Security).
 *
 * <h2>Why a CA at all</h2>
 *
 * <p>Because the thing under test is mutual authentication with a pinned certificate, and a test
 * that skipped the certificates would be testing a channel rather than the design. The deployment's
 * own PKI is created by {@code deploy/setup.sh} with {@code openssl}; this is the same shape,
 * produced in Java so that it works identically on every platform.
 */
final class TestPki {

  /** The generated files and the pin that goes with them. */
  record Identity(Path bundle, String fingerprint) {}

  private final KeyPair caKeys;
  private final X509Certificate caCertificate;
  private final Path directory;

  private TestPki(Path directory, KeyPair caKeys, X509Certificate caCertificate) {
    this.directory = directory;
    this.caKeys = caKeys;
    this.caCertificate = caCertificate;
  }

  /**
   * Creates an authority in a temporary directory.
   *
   * @return the authority, ready to issue identities
   * @throws Exception when key generation or signing fails, which fails the test rather than being
   *     handled — a test that cannot make a certificate cannot test anything below
   */
  static TestPki create() throws Exception {
    Path directory = Files.createTempDirectory("homeinv-test-pki-");
    directory.toFile().deleteOnExit();

    KeyPair keys = keyPair();
    X509Certificate certificate =
        sign(keys, keys.getPrivate(), "Home Inventory test CA", null, true);
    return new TestPki(directory, keys, certificate);
  }

  /**
   * Issues one identity as a PEM bundle: key, certificate, CA.
   *
   * <p>One file, in that order, because a runtime secret is one file — three mounts would be three
   * things that can get out of step with each other.
   *
   * @param commonName the name the certificate carries, which is also its subject alternative name
   * @return where the bundle is, and the SHA-256 fingerprint of its certificate
   * @throws Exception when signing or writing fails
   */
  Identity issue(String commonName) throws Exception {
    KeyPair keys = keyPair();
    X509Certificate certificate = sign(keys, caKeys.getPrivate(), commonName, caCertificate, false);

    Path bundle = directory.resolve(commonName + ".pem");
    StringWriter out = new StringWriter();
    try (JcaPEMWriter writer = new JcaPEMWriter(out)) {
      writer.writeObject(keys.getPrivate());
      writer.writeObject(certificate);
      writer.writeObject(caCertificate);
    }
    Files.writeString(bundle, out.toString(), StandardCharsets.UTF_8);
    bundle.toFile().deleteOnExit();

    return new Identity(bundle, fingerprintOf(certificate));
  }

  private static KeyPair keyPair() throws Exception {
    // RSA rather than Ed25519: `grpc-netty-shaded` negotiates it everywhere, and
    // what is under test is the pinning, not the signature algorithm. 2048 bits
    // is a second of CPU per run and nobody is attacking a key that lives for
    // the length of a test.
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048, new SecureRandom());
    return generator.generateKeyPair();
  }

  private static X509Certificate sign(
      KeyPair subject, PrivateKey issuerKey, String commonName, X509Certificate issuer, boolean ca)
      throws OperatorCreationException,
          IOException,
          java.security.NoSuchAlgorithmException,
          java.security.cert.CertificateException {

    X500Name subjectName = new X500Name("CN=" + commonName);
    X500Name issuerName = issuer == null ? subjectName : new X500Name(issuer.getSubjectX500Principal().getName());
    Instant now = Instant.now();

    JcaX509v3CertificateBuilder builder =
        new JcaX509v3CertificateBuilder(
            issuerName,
            BigInteger.valueOf(now.toEpochMilli()),
            Date.from(now.minus(Duration.ofMinutes(5))),
            Date.from(now.plus(Duration.ofDays(1))),
            subjectName,
            subject.getPublic());

    builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(ca));
    if (!ca) {
      // The hostname a client would verify. The pinned trust manager does not
      // check it — pinning answers a different question — but a certificate
      // without a SAN is one that no ordinary TLS client would accept, and a
      // fixture that could not be used normally is a fixture that hides problems.
      builder.addExtension(
          Extension.subjectAlternativeName,
          false,
          new GeneralNames(new GeneralName(GeneralName.dNSName, commonName)));
      builder.addExtension(
          Extension.subjectKeyIdentifier,
          false,
          new JcaX509ExtensionUtils().createSubjectKeyIdentifier(subject.getPublic()));
    }

    X509CertificateHolder holder =
        builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(issuerKey));
    return new JcaX509CertificateConverter().getCertificate(holder);
  }

  private static String fingerprintOf(X509Certificate certificate) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    return HexFormat.of().formatHex(digest.digest(certificate.getEncoded()));
  }
}
