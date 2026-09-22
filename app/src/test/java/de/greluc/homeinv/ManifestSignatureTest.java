/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.homeinv.plugins.domain.ManifestSignature;
import de.greluc.homeinv.plugins.domain.ManifestSignature.State;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * What the core may conclude from a manifest's signature (REQ-PLG-004).
 *
 * <h2>Every key here is made in the test and thrown away</h2>
 *
 * <p>Nothing is read from a file and nothing is committed. A private key in a fixture would be a
 * private key in a public repository, and "it is only a test key" is the sentence under every one
 * of those.
 *
 * <h2>Why this is the same thing cosign produces</h2>
 *
 * <p>{@code cosign sign-blob --key k.pem file} signs {@code SHA-256(file)} with ECDSA and prints the
 * ASN.1 signature in base64. {@code SHA256withECDSA} in the JDK is that same construction, so a
 * signature made here is byte-comparable with one made there — which was checked against
 * {@code openssl dgst -sha256 -sign} before this test was written, since the two agree by
 * construction and a claim of interoperability is worth no more than the check behind it.
 *
 * <p>No container, no Spring.
 */
@DisplayName("A plugin manifest's signature")
class ManifestSignatureTest {

  /** A manifest is verified as the bytes it arrived as, so the test uses bytes too. */
  private static final byte[] MANIFEST =
      """
      apiVersion: home-inv.plugin/v1
      metadata:
        id: de.greluc.homeinv.plugin.example
        version: "1.0.0"
      """
          .getBytes(StandardCharsets.UTF_8);

  @Test
  @DisplayName("verifies when the operator's key is the one that signed it")
  void theRightKeyVerifies() throws Exception {
    KeyPair signer = ecKeyPair();

    ManifestSignature.Result result =
        ManifestSignature.verify(MANIFEST, sign(signer, "SHA256withECDSA", MANIFEST), publicKey(signer));

    assertThat(result.state()).isEqualTo(State.VERIFIED);
    assertThat(result.reason()).as("a check that passed has nothing to say").isEmpty();
    assertThat(result.acceptable()).isTrue();
  }

  @Test
  @DisplayName("fails when a single byte of the manifest changed after signing")
  void anAlteredManifestFails() throws Exception {
    KeyPair signer = ecKeyPair();
    String signature = sign(signer, "SHA256withECDSA", MANIFEST);
    byte[] altered = new String(MANIFEST, StandardCharsets.UTF_8).replace("1.0.0", "1.0.1").getBytes(StandardCharsets.UTF_8);

    ManifestSignature.Result result = ManifestSignature.verify(altered, signature, publicKey(signer));

    assertThat(result.state()).isEqualTo(State.INVALID);
    assertThat(result.reason()).contains("not over this manifest");
    assertThat(result.acceptable()).as("an altered manifest is never run").isFalse();
  }

  @Test
  @DisplayName("fails when somebody else's key signed it")
  void aForeignSignatureFails() throws Exception {
    KeyPair somebodyElse = ecKeyPair();
    KeyPair theOperatorTrusts = ecKeyPair();

    ManifestSignature.Result result =
        ManifestSignature.verify(
            MANIFEST, sign(somebodyElse, "SHA256withECDSA", MANIFEST), publicKey(theOperatorTrusts));

    assertThat(result.state()).isEqualTo(State.INVALID);
  }

  @Test
  @DisplayName("is unsigned, not invalid, when neither a signature nor a key exists")
  void nothingAtAllIsUnsigned() {
    ManifestSignature.Result result = ManifestSignature.verify(MANIFEST, null, null);

    assertThat(result.state()).as("the case REQ-PLG-004 lets an operator permit").isEqualTo(State.UNSIGNED);
    assertThat(result.acceptable()).isTrue();
  }

  @Test
  @DisplayName("is invalid when a key is installed and the manifest brings no signature")
  void aKeyWithoutASignatureIsInvalid() throws Exception {
    ManifestSignature.Result result = ManifestSignature.verify(MANIFEST, "  ", publicKey(ecKeyPair()));

    assertThat(result.state())
        .as("the operator installed the means to check and got nothing to check")
        .isEqualTo(State.INVALID);
    assertThat(result.reason()).contains("brings no signature");
  }

  @Test
  @DisplayName("is invalid when it brings a signature nobody installed a key for")
  void aSignatureWithoutAKeyIsInvalid() throws Exception {
    ManifestSignature.Result result =
        ManifestSignature.verify(MANIFEST, sign(ecKeyPair(), "SHA256withECDSA", MANIFEST), null);

    assertThat(result.state()).isEqualTo(State.INVALID);
    assertThat(result.reason()).contains("no public key is installed");
  }

  @Test
  @DisplayName("reads the operator's key as PEM exactly as it reads it bare")
  void pemAndBareBase64Agree() throws Exception {
    KeyPair signer = ecKeyPair();
    String signature = sign(signer, "SHA256withECDSA", MANIFEST);
    String bare = publicKey(signer);
    String pem = "-----BEGIN PUBLIC KEY-----\n" + bare.replaceAll("(.{64})", "$1\n") + "\n-----END PUBLIC KEY-----\n";

    assertThat(ManifestSignature.verify(MANIFEST, signature, pem).state())
        .as("cosign prints PEM and a YAML value holds one line; both are what an operator has")
        .isEqualTo(State.VERIFIED);
    assertThat(ManifestSignature.verify(MANIFEST, signature, bare).state()).isEqualTo(State.VERIFIED);
  }

  @ParameterizedTest(name = "{0}")
  @DisplayName("verifies for every key kind cosign can hold")
  @CsvSource({"EC,SHA256withECDSA", "Ed25519,Ed25519", "RSA,SHA256withRSA"})
  void everyKeyKindVerifies(String keyAlgorithm, String signatureAlgorithm) throws Exception {
    KeyPair signer = keyPair(keyAlgorithm);

    ManifestSignature.Result result =
        ManifestSignature.verify(MANIFEST, sign(signer, signatureAlgorithm, MANIFEST), publicKey(signer));

    assertThat(result.state()).isEqualTo(State.VERIFIED);
  }

  @ParameterizedTest(name = "{0}")
  @DisplayName("refuses input that is not a signature and not a key")
  @CsvSource({
    "not base64 at all!!, refused because it is not base64",
    "aGVsbG8=, refused because it is not a signature"
  })
  void nonsenseIsRefused(String signature, String why) throws Exception {
    ManifestSignature.Result result =
        ManifestSignature.verify(MANIFEST, signature, publicKey(ecKeyPair()));

    assertThat(result.state()).as(why).isEqualTo(State.INVALID);
  }

  @Test
  @DisplayName("refuses a key this core cannot read rather than throwing on it")
  void anUnreadableKeyIsRefused() {
    ManifestSignature.Result result =
        ManifestSignature.verify(MANIFEST, "aGVsbG8=", Base64.getEncoder().encodeToString("not a key".getBytes(StandardCharsets.UTF_8)));

    assertThat(result.state()).isEqualTo(State.INVALID);
    assertThat(result.reason()).contains("not one this core can read");
  }

  @Test
  @DisplayName("refuses anything longer than a signature or a key can be")
  void anAbsurdlyLongValueIsRefused() throws Exception {
    String tooLong = "A".repeat(9 * 1024);

    assertThat(ManifestSignature.verify(MANIFEST, tooLong, publicKey(ecKeyPair())).state())
        .as("the cheapest bound there is, taken before any parsing")
        .isEqualTo(State.INVALID);
  }

  @Test
  @DisplayName("reads text and bytes as the same document")
  void theTextOverloadAgreesWithTheBytes() throws Exception {
    KeyPair signer = ecKeyPair();
    String signature = sign(signer, "SHA256withECDSA", MANIFEST);

    assertThat(
            ManifestSignature.verify(
                    new String(MANIFEST, StandardCharsets.UTF_8), signature, publicKey(signer))
                .state())
        .isEqualTo(State.VERIFIED);
  }

  /**
   * A throwaway P-256 pair, which is what {@code cosign generate-key-pair} makes.
   *
   * @return the pair
   * @throws Exception when the JRE has no EC, which would be a broken JRE
   */
  private static KeyPair ecKeyPair() throws Exception {
    return keyPair("EC");
  }

  /**
   * A throwaway key pair of one kind.
   *
   * @param algorithm {@code EC}, {@code Ed25519} or {@code RSA}
   * @return the pair
   * @throws Exception when the algorithm is not available
   */
  private static KeyPair keyPair(String algorithm) throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance(algorithm);
    switch (algorithm) {
      case "EC" -> generator.initialize(new ECGenParameterSpec("secp256r1"));
      case "RSA" -> generator.initialize(2048);
      default -> {
        // Ed25519 has one parameter set and takes no initialisation.
      }
    }
    return generator.generateKeyPair();
  }

  /**
   * Signs bytes the way {@code cosign sign-blob} does: the raw payload, base64 out.
   *
   * @param pair whose private half signs
   * @param algorithm the JCA signature algorithm
   * @param payload what is signed
   * @return the base64 signature
   * @throws Exception when the algorithm is not available
   */
  private static String sign(KeyPair pair, String algorithm, byte[] payload) throws Exception {
    Signature signer = Signature.getInstance(algorithm);
    signer.initSign(pair.getPrivate());
    signer.update(payload);
    return Base64.getEncoder().encodeToString(signer.sign());
  }

  /**
   * The public half as the bare base64 of its {@code SubjectPublicKeyInfo}.
   *
   * @param pair the pair
   * @return the base64
   */
  private static String publicKey(KeyPair pair) {
    return Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
  }
}
