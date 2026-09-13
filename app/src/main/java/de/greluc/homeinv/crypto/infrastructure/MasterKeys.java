/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.crypto.infrastructure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The master keys that wrap each tenant's data key (ADR-0019, REQ-SEC-049).
 *
 * <h2>Two active versions, in two files</h2>
 *
 * <p>{@code REQ-SEC-049} requires the master key to support two active versions, so that a rotation
 * can re-wrap every tenant's data key without touching a single ciphertext. Decided 2026-09-13: the
 * second version is a <b>second file</b> rather than a second line in the first one.
 *
 * <p>Every other secret in this deployment is one file holding one key — {@code credential-key},
 * {@code url-signing-key} — and that is what a container runtime's secret is. A file with a syntax
 * of its own would need a parser here and tooling everywhere else; two files need neither, and the
 * rotation reads as what it is: mount the new key, keep the old one mounted until the re-wrap is
 * done, unmount it.
 *
 * <ul>
 *   <li>{@code HOMEINV_DATA_ENCRYPTION_MASTER_KEY_FILE} — the key that wraps from now on
 *   <li>{@code HOMEINV_DATA_ENCRYPTION_MASTER_KEY_VERSION} — which version that is, default 1
 *   <li>{@code HOMEINV_DATA_ENCRYPTION_PREVIOUS_MASTER_KEY_FILE} — optional, the version below it,
 *       mounted only while a rotation is in progress
 * </ul>
 *
 * <p>The version is what a wrapped key records, and it is bound into the wrapping's AAD — so a
 * wrapped data key cannot be replayed as if another master key had produced it.
 */
@Component
public class MasterKeys {

  /** AES-256, so each file has to hold at least that much. */
  private static final int KEY_BYTES = 32;

  /** The version that wraps from now on. */
  private final int activeVersion;

  /** Every version this instance can unwrap with, newest first. */
  private final Map<Integer, SecretKeySpec> keys = new LinkedHashMap<>();

  /**
   * Reads the master keys and refuses to start without one.
   *
   * @param keyFile the path from {@code HOMEINV_DATA_ENCRYPTION_MASTER_KEY_FILE}
   * @param version the path's version, from {@code HOMEINV_DATA_ENCRYPTION_MASTER_KEY_VERSION}
   * @param previousFile the path from {@code HOMEINV_DATA_ENCRYPTION_PREVIOUS_MASTER_KEY_FILE}, or
   *     blank when no rotation is in progress
   * @throws IllegalStateException when the active key is missing, unreadable or too short. Startup
   *     fails rather than a key being generated: a generated one differs per instance, and every
   *     value written under it would be unreadable by the next process to start — which is data
   *     loss that looks like a configuration change
   */
  public MasterKeys(
      @Value("${homeinv.security.data-encryption-master-key-file:}") String keyFile,
      @Value("${homeinv.security.data-encryption-master-key-version:1}") int version,
      @Value("${homeinv.security.data-encryption-previous-master-key-file:}") String previousFile) {

    if (keyFile == null || keyFile.isBlank()) {
      throw new IllegalStateException(
          "HOMEINV_DATA_ENCRYPTION_MASTER_KEY_FILE is not set. It wraps every tenant's data key "
              + "(ADR-0019) and has no default: a generated one differs per instance, so every "
              + "sealed value would become unreadable the next time a process started.");
    }
    if (version < 1 || version > 255) {
      throw new IllegalStateException(
          "HOMEINV_DATA_ENCRYPTION_MASTER_KEY_VERSION is " + version + "; it must be 1..255, "
              + "because the version is one byte of the wrapping's authenticated data.");
    }
    this.activeVersion = version;
    keys.put(version, read(keyFile, "the data encryption master key"));

    if (previousFile != null && !previousFile.isBlank()) {
      if (version == 1) {
        throw new IllegalStateException(
            "A previous master key is mounted while the active version is 1. The previous version "
                + "would be 0, which is not a version; bump "
                + "HOMEINV_DATA_ENCRYPTION_MASTER_KEY_VERSION when you mount a new key.");
      }
      keys.put(version - 1, read(previousFile, "the previous data encryption master key"));
    }
  }

  /**
   * Which version wraps a data key issued now.
   *
   * @return the active version
   */
  public int activeVersion() {
    return activeVersion;
  }

  /**
   * The key of one version.
   *
   * @param version the version a wrapped data key records
   * @return the key
   * @throws IllegalStateException when this instance has no such version, which means a key was
   *     unmounted while data still referenced it
   */
  public SecretKeySpec of(int version) {
    SecretKeySpec key = keys.get(version);
    if (key == null) {
      throw new IllegalStateException(
          "A tenant's data key is wrapped with master key version "
              + version
              + ", which this instance does not have. Mount it as "
              + "HOMEINV_DATA_ENCRYPTION_PREVIOUS_MASTER_KEY_FILE until the re-wrap has finished.");
    }
    return key;
  }

  /**
   * Reads one key file.
   *
   * <p>Base64 is accepted as well as raw bytes, the accommodation every other key in this
   * deployment makes: a key handed over as text is what a secret manager usually produces, and
   * sealing under the literal characters of a base64 string would work and be wrong.
   *
   * @param path the file
   * @param what the key's name, for the message
   * @return the key
   */
  private static SecretKeySpec read(String path, String what) {
    byte[] bytes;
    try {
      bytes = Files.readAllBytes(Path.of(path));
    } catch (IOException unreadable) {
      throw new IllegalStateException(what + " at " + path + " could not be read", unreadable);
    }
    byte[] material = decodeBase64(bytes);
    if (material.length < KEY_BYTES) {
      throw new IllegalStateException(
          "%s is %d bytes; at least %d are required."
              .formatted(what, material.length, KEY_BYTES));
    }
    return new SecretKeySpec(material, 0, KEY_BYTES, "AES");
  }

  /**
   * The file's bytes, base64-decoded when that is what they are.
   *
   * @param raw what the file held
   * @return the key material
   */
  private static byte[] decodeBase64(byte[] raw) {
    try {
      return Base64.getMimeDecoder().decode(new String(raw, java.nio.charset.StandardCharsets.US_ASCII).trim());
    } catch (IllegalArgumentException notBase64) {
      return raw;
    }
  }
}
