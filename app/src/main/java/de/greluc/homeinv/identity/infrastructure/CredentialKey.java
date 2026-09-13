/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.identity.infrastructure;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The key that seals a TOTP secret at rest (REQ-AUTH-002, REQ-SEC-050).
 *
 * <h2>Why a secret is sealed at all</h2>
 *
 * <p>A password is hashed and never recovered; a TOTP secret cannot be, because verifying a code
 * means generating one from the same secret. Stored as it stands, it is a second factor anybody
 * holding a database dump can produce — which is precisely the attack a second factor exists for.
 * Sealed here, a dump is not enough: the key is a file the deployment mounts, and it is in no
 * backup of the database.
 *
 * <h2>Its own key, and not one of the others</h2>
 *
 * <p>Not {@code url-signing-key}: rotating the key that signs media URLs must not invalidate every
 * account's authenticator. Not {@code data-encryption-master-key} either, which wraps the
 * per-tenant data keys of ADR-0019 — a credential belongs to a person and not to a tenant, and
 * borrowing a tenant's key for it would be a key hierarchy with one wrong edge in it.
 *
 * <p>Read from a <em>file</em>, like every other secret (06 §6.11): an environment variable is
 * readable by anything that can list the process and survives in crash dumps and shell history.
 */
@Component
public class CredentialKey {

  /** AES-256, so the file has to hold at least that much. */
  private static final int KEY_BYTES = 32;

  /** The nonce length GCM is specified for; 12 bytes is the only one with no extra derivation. */
  private static final int IV_BYTES = 12;

  /** The tag length in bits, and the longest GCM offers. */
  private static final int TAG_BITS = 128;

  /** What a sealed value starts with, so a later key or algorithm can be told from this one. */
  private static final String PREFIX = "v1:";

  private final SecretKeySpec key;
  private final SecureRandom random = new SecureRandom();

  /**
   * Reads and validates the key.
   *
   * @param keyFile the path from {@code HOMEINV_CREDENTIAL_KEY_FILE}
   * @throws IllegalStateException when the file is missing, unreadable or too short. Startup fails
   *     rather than a key being generated: a generated one differs per instance, so an account
   *     enrolled on one would be locked out by the next, and nobody would trace that back to here
   */
  public CredentialKey(@Value("${homeinv.security.credential-key-file:}") String keyFile) {
    if (keyFile == null || keyFile.isBlank()) {
      throw new IllegalStateException(
          "HOMEINV_CREDENTIAL_KEY_FILE is not set. It seals the second-factor secrets of "
              + "REQ-AUTH-002 and has no default, because a generated one differs per instance "
              + "and would lock out every account enrolled against another.");
    }

    byte[] read;
    try {
      read = Files.readAllBytes(Path.of(keyFile));
    } catch (IOException unreadable) {
      throw new IllegalStateException(
          "The credential key at " + keyFile + " could not be read", unreadable);
    }

    // A key handed over as text is what a secret manager usually produces, and
    // sealing under the literal characters of a base64 string would work and be
    // wrong — the same accommodation UrlSigningKey makes.
    byte[] decoded = tryDecodeBase64(read);
    byte[] material = decoded != null ? decoded : read;

    if (material.length < KEY_BYTES) {
      throw new IllegalStateException(
          "The credential key is %d bytes; at least %d are required."
              .formatted(material.length, KEY_BYTES));
    }
    this.key = new SecretKeySpec(material, 0, KEY_BYTES, "AES");
  }

  /**
   * Seals a secret for storage.
   *
   * <p>A fresh nonce per call, which AES-GCM requires absolutely: two values sealed under one key
   * and one nonce reveal the difference between them and forge the tag.
   *
   * @param plaintext the secret, which the caller must not store anywhere else
   * @return {@code v1:<nonce>:<ciphertext>}, both base64
   */
  public String seal(byte[] plaintext) {
    byte[] iv = new byte[IV_BYTES];
    random.nextBytes(iv);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
      byte[] sealed = cipher.doFinal(plaintext);
      Base64.Encoder encoder = Base64.getEncoder().withoutPadding();
      return PREFIX + encoder.encodeToString(iv) + ":" + encoder.encodeToString(sealed);
    } catch (GeneralSecurityException failed) {
      throw new IllegalStateException("The credential secret could not be sealed.", failed);
    }
  }

  /**
   * Opens a sealed secret.
   *
   * @param sealed what {@link #seal(byte[])} produced
   * @return the secret
   * @throws IllegalStateException when the value was not sealed by this key, which is a tampered or
   *     a mis-mounted key rather than a bad request — the tag is what makes the two the same thing
   */
  public byte[] open(String sealed) {
    if (sealed == null || !sealed.startsWith(PREFIX)) {
      throw new IllegalStateException("A stored credential is not in the sealed format.");
    }
    String[] parts = sealed.substring(PREFIX.length()).split(":", 2);
    if (parts.length != 2) {
      throw new IllegalStateException("A stored credential is not in the sealed format.");
    }
    try {
      Base64.Decoder decoder = Base64.getDecoder();
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, decoder.decode(parts[0])));
      return cipher.doFinal(decoder.decode(parts[1]));
    } catch (GeneralSecurityException | IllegalArgumentException failed) {
      throw new IllegalStateException(
          "A stored credential could not be opened with the mounted credential key.", failed);
    }
  }

  private static byte[] tryDecodeBase64(byte[] raw) {
    try {
      return Base64.getMimeDecoder().decode(new String(raw, StandardCharsets.UTF_8).trim());
    } catch (IllegalArgumentException notBase64) {
      return null;
    }
  }
}
