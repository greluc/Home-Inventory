/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.plugin.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Splitting the mounted identity, and the one thing that must not end up in a trust store.
 *
 * <p>The blocks here are not keys or certificates — they are labelled text in the shape a PEM
 * bundle has. What is under test is the splitting: which block becomes the chain, which becomes the
 * key, and which becomes the authority a client certificate is verified against. Real cryptography
 * would prove a TLS library works, which is not in doubt.
 */
@DisplayName("A mounted identity")
class PemTest {

  private static final String KEY =
      "-----BEGIN PRIVATE KEY-----\nthe-key\n-----END PRIVATE KEY-----\n";
  private static final String LEAF =
      "-----BEGIN CERTIFICATE-----\nthe-plugin\n-----END CERTIFICATE-----\n";
  private static final String AUTHORITY =
      "-----BEGIN CERTIFICATE-----\nthe-ca\n-----END CERTIFICATE-----\n";

  @Test
  @DisplayName("is split into the chain, the key and the authority")
  void theThreeParts(@TempDir Path directory) throws Exception {
    Path bundle = directory.resolve("mtls-plugin-oidc");
    Files.writeString(bundle, KEY + LEAF + AUTHORITY, StandardCharsets.UTF_8);

    Pem.Bundle read = Pem.read(bundle);

    assertThat(text(read.key())).contains("the-key");
    assertThat(text(read.chain())).contains("the-plugin").doesNotContain("the-ca");
    // THE ONE THAT MATTERS. A trust store holding this plugin's own certificate
    // would accept it as a caller, and a plugin is called by the core and by
    // nothing else. A trust store holding the private key would be worse.
    assertThat(text(read.authority()))
        .contains("the-ca")
        .doesNotContain("the-plugin")
        .doesNotContain("the-key");
  }

  @Test
  @DisplayName("of one certificate is its own chain and its own authority")
  void aSelfSignedBundle(@TempDir Path directory) throws Exception {
    // What `setup.sh` writes when the CA and the certificate are the same thing.
    // The chain may not be empty, so the single certificate serves as both.
    Path bundle = directory.resolve("mtls-plugin-oidc");
    Files.writeString(bundle, KEY + LEAF, StandardCharsets.UTF_8);

    Pem.Bundle read = Pem.read(bundle);

    assertThat(text(read.chain())).contains("the-plugin");
    assertThat(text(read.authority())).contains("the-plugin");
  }

  @Test
  @DisplayName("that is missing or incomplete stops the plugin rather than starting it")
  void anIncompleteBundle(@TempDir Path directory) throws Exception {
    Path noKey = directory.resolve("no-key");
    Files.writeString(noKey, LEAF, StandardCharsets.UTF_8);
    assertThatThrownBy(() -> Pem.read(noKey))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("private key");

    Path noCertificate = directory.resolve("no-certificate");
    Files.writeString(noCertificate, KEY, StandardCharsets.UTF_8);
    assertThatThrownBy(() -> Pem.read(noCertificate)).isInstanceOf(IOException.class);

    assertThatThrownBy(() -> Pem.read(directory.resolve("not-there")))
        .isInstanceOf(IOException.class);
  }

  private static String text(InputStream stream) throws IOException {
    try (stream) {
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
