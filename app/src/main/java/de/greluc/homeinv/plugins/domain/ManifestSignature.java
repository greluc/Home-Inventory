/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.plugins.domain;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;

/**
 * Whether a manifest is the document its publisher signed (REQ-PLG-004).
 *
 * <h2>What is checked, and against what</h2>
 *
 * <p>A plugin's manifest is what the core registers, what an administrator consents to and what
 * every capability check is answered from. It arrives from outside the deployment, so "is this the
 * document its publisher wrote" has to be answerable without asking anyone — which is what a
 * detached signature over the manifest bytes is for.
 *
 * <p>The signature is the one {@code cosign sign-blob} produces: base64, over the bytes of the
 * document itself. It is verified against a public key the <b>operator</b> installed beside the
 * plugin, never one the manifest names — a document carrying the key to check it with would be
 * checking itself.
 *
 * <h2>Why a key and not keyless</h2>
 *
 * <p>Sigstore's keyless flow verifies a certificate against Fulcio and a transparency entry against
 * Rekor, and both are network calls. {@code api} has no outbound route at all (ADR-0026), so a core
 * that verified keylessly would either not verify or would need the one thing the topology exists to
 * deny it. Key-based verification is arithmetic over bytes already in hand, which is what an offline
 * check has to be.
 *
 * <p>The <b>image</b> is a different question with a different answer: it is verified at deploy time
 * by a one-shot service that does reach the registry, because no manifest can attest to a digest
 * that is computed over the image (ADR-0085).
 *
 * <h2>Three outcomes, not two</h2>
 *
 * <p>{@link State#UNSIGNED} and {@link State#INVALID} are kept apart deliberately. An unsigned
 * plugin is one whose publisher did not sign — permitted where an operator explicitly allowed it,
 * which is what REQ-PLG-004 says. An <i>invalid</i> signature is a document that does not match the
 * signature travelling with it, and no operator setting makes that acceptable: it means the manifest
 * was altered after signing, or that the key is not the one that signed it. The two therefore reach
 * different ends, and collapsing them into one boolean is exactly what would let the second pass as
 * the first.
 *
 * <p>No framework, no I/O, no logging: this is the rule itself.
 */
public final class ManifestSignature {

  /**
   * The key algorithms {@code cosign} can hold, in the order they are tried.
   *
   * <p>A {@code SubjectPublicKeyInfo} names its own algorithm by OID, and the JDK offers no way to
   * read that name without first choosing a {@link KeyFactory} — so the choice is made by trying,
   * which is at most three attempts over a few dozen bytes. {@code EC} is first because
   * {@code cosign generate-key-pair} produces P-256 and therefore nearly every key here will be one.
   */
  private static final List<String> KEY_ALGORITHMS = List.of("EC", "Ed25519", "RSA");

  /** The longest signature or key this will look at. Beyond it, the input is not one. */
  private static final int MAX_ENCODED = 8 * 1024;

  /** A rule, not a thing to instantiate. Empty, so JaCoCo filters it out as it does the others. */
  private ManifestSignature() {}

  /** What a verification found. */
  public enum State {

    /** The signature is over these bytes, and the operator's key made it. */
    VERIFIED,

    /** No signature, no key, or neither. Permitted only where the operator said so. */
    UNSIGNED,

    /** A signature that is present and does not verify. Never permitted. */
    INVALID
  }

  /**
   * The outcome of one verification.
   *
   * @param state what was found
   * @param reason a sentence an operator can act on, empty for {@link State#VERIFIED} because there
   *     is nothing to say about a check that passed
   */
  public record Result(State state, String reason) {

    /**
     * Whether this plugin may be registered as callable at all.
     *
     * @return true unless a signature was present and wrong
     */
    public boolean acceptable() {
      return state != State.INVALID;
    }
  }

  /**
   * Verifies a detached signature over a manifest.
   *
   * <p>Every failure is a {@link Result} and never an exception: this runs while the core is
   * starting, and foreign input must never be able to stop that (REQ-PLG-008, 09 §9.11). A malformed
   * key, a signature that is not base64 and a key of a kind no provider here holds are all
   * {@link State#INVALID} — each means the operator meant to have a check and has not got one, which
   * is precisely what an unsigned plugin is not.
   *
   * @param manifest the document as it was read, byte for byte; re-serialising it would change what
   *     is being verified
   * @param signature the base64 signature {@code cosign sign-blob} printed, or {@code null} or blank
   *     when the plugin brings none
   * @param publicKey the operator's key for this plugin, PEM or the bare base64 of its DER, or
   *     {@code null} or blank when none was installed
   * @return what was found, never {@code null}
   */
  public static Result verify(byte[] manifest, String signature, String publicKey) {
    boolean hasSignature = signature != null && !signature.isBlank();
    boolean hasKey = publicKey != null && !publicKey.isBlank();
    if (!hasSignature && !hasKey) {
      return new Result(State.UNSIGNED, "no signature travels with it and no key is installed for it");
    }
    if (!hasSignature) {
      // A key with no signature is an operator who installed the means to check
      // and received nothing to check. Refused rather than read as unsigned:
      // they have already said what they expect to be there.
      return new Result(
          State.INVALID, "a public key is installed for it and its manifest brings no signature");
    }
    if (!hasKey) {
      return new Result(
          State.INVALID, "it brings a signature and no public key is installed to check it against");
    }
    if (signature.length() > MAX_ENCODED || publicKey.length() > MAX_ENCODED) {
      return new Result(State.INVALID, "its signature or its key is larger than either can be");
    }

    byte[] signatureBytes;
    byte[] keyBytes;
    try {
      signatureBytes = Base64.getMimeDecoder().decode(signature.trim());
      keyBytes = Base64.getMimeDecoder().decode(withoutArmour(publicKey));
    } catch (IllegalArgumentException notBase64) {
      return new Result(State.INVALID, "its signature or its public key is not base64");
    }

    PublicKey key = publicKeyFrom(keyBytes);
    if (key == null) {
      return new Result(State.INVALID, "its public key is not one this core can read");
    }
    String algorithm = signatureAlgorithmFor(key);
    if (algorithm == null) {
      return new Result(
          State.INVALID,
          "its public key is a " + key.getAlgorithm() + " key, which nothing signs a manifest with");
    }

    try {
      Signature verifier = Signature.getInstance(algorithm);
      verifier.initVerify(key);
      verifier.update(manifest);
      return verifier.verify(signatureBytes)
          ? new Result(State.VERIFIED, "")
          : new Result(State.INVALID, "its signature is not over this manifest, or not by this key");
    } catch (GeneralSecurityException | RuntimeException refused) {
      // `Signature.verify` throws on bytes that are not a well-formed structure
      // for the algorithm. That is a failed verification and not a fault of
      // ours: a stranger's document is allowed to be nonsense.
      return new Result(
          State.INVALID, "its signature could not be checked: " + refused.getMessage());
    }
  }

  /**
   * Verifies a signature over a manifest held as text.
   *
   * <p>For callers holding the document rather than the bytes. UTF-8, which is what a manifest is
   * read and stored as.
   *
   * @param manifest the document
   * @param signature the base64 signature, or {@code null}
   * @param publicKey the operator's key, or {@code null}
   * @return what was found
   */
  public static Result verify(String manifest, String signature, String publicKey) {
    return verify(manifest.getBytes(StandardCharsets.UTF_8), signature, publicKey);
  }

  /**
   * The base64 body of a PEM block, or the input when it is already bare.
   *
   * <p>Both are accepted because both are what an operator has to hand: {@code cosign public-key}
   * prints PEM, and one line is what survives a YAML value without a block scalar. Refusing either
   * would be a rule about formatting, enforced at the point where a deployment stops working.
   *
   * @param key what was configured
   * @return the base64, with armour and all whitespace removed
   */
  private static String withoutArmour(String key) {
    return key.replaceAll("-----(BEGIN|END)[^-]*-----", "").replaceAll("\\s", "");
  }

  /**
   * Reads a {@code SubjectPublicKeyInfo} without being told what is inside it.
   *
   * @param der the encoded key
   * @return the key, or {@code null} when no algorithm here could read it
   */
  private static PublicKey publicKeyFrom(byte[] der) {
    X509EncodedKeySpec spec = new X509EncodedKeySpec(der);
    for (String algorithm : KEY_ALGORITHMS) {
      try {
        return KeyFactory.getInstance(algorithm).generatePublic(spec);
      } catch (GeneralSecurityException | RuntimeException wrongKind) {
        // Not this one. The next is tried, and exhausting them all is the answer.
      }
    }
    return null;
  }

  /**
   * Which signature algorithm goes with a key.
   *
   * <p>SHA-256 for EC and RSA, because that is what {@code cosign} signs a blob with. Ed25519 names
   * its own hash and takes no prefix.
   *
   * @param key the parsed public key
   * @return the JCA algorithm name, or {@code null} for a kind nothing signs manifests with
   */
  private static String signatureAlgorithmFor(PublicKey key) {
    return switch (key.getAlgorithm()) {
      case "EC" -> "SHA256withECDSA";
      case "Ed25519", "EdDSA" -> "Ed25519";
      case "RSA" -> "SHA256withRSA";
      default -> null;
    };
  }
}
