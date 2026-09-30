/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.plugin.oidc;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The mounted identity, split into the parts a TLS server wants.
 *
 * <p>{@code deploy/setup.sh} writes one file per identity — the private key, this plugin's
 * certificate and the CA that signed it, in that order. One file rather than three, because a
 * runtime secret is one file and three mounts are three things that can get out of step.
 *
 * <p>gRPC wants them apart: a key and a chain for the server's own identity, and a separate trust
 * store for verifying the core's client certificate. Handing it the whole bundle as a trust store
 * would hand it a private key as well, which is a trust store nobody should build.
 */
public final class Pem {

  private Pem() {}

  /**
   * Splits a PEM bundle into its blocks, in the order they appear.
   *
   * @param path the mounted bundle
   * @return the blocks, each complete with its own header and footer
   * @throws IOException when the file cannot be read or holds no PEM at all
   */
  public static Bundle read(Path path) throws IOException {
    String text = Files.readString(path, StandardCharsets.UTF_8);
    List<String> keys = new ArrayList<>();
    List<String> certificates = new ArrayList<>();

    int from = 0;
    while (true) {
      int begin = text.indexOf("-----BEGIN ", from);
      if (begin < 0) {
        break;
      }
      int headerEnd = text.indexOf("-----", begin + "-----BEGIN ".length());
      if (headerEnd < 0) {
        break;
      }
      String label = text.substring(begin + "-----BEGIN ".length(), headerEnd);
      String footer = "-----END " + label + "-----";
      int end = text.indexOf(footer, headerEnd);
      if (end < 0) {
        break;
      }
      String block = text.substring(begin, end + footer.length()) + "\n";
      if (label.contains("PRIVATE KEY")) {
        keys.add(block);
      } else if (label.contains("CERTIFICATE")) {
        certificates.add(block);
      }
      from = end + footer.length();
    }

    if (keys.isEmpty() || certificates.isEmpty()) {
      throw new IOException(
          path
              + " is not an identity: it needs a private key and at least one certificate. It is"
              + " required and has no default, because a plugin that came up without mTLS would"
              + " take calls from anything on its segment and nothing about it would say so.");
    }
    return new Bundle(keys.get(0), certificates);
  }

  /**
   * One identity, in the three pieces a TLS server needs.
   *
   * @param privateKey the key block
   * @param certificates this plugin's certificate first, the CA that signed it last
   */
  public record Bundle(String privateKey, List<String> certificates) {

    /**
     * The chain this plugin presents: everything but the root.
     *
     * @return the certificate blocks, joined
     */
    public InputStream chain() {
      String joined =
          certificates.size() == 1
              ? certificates.get(0)
              : String.join("", certificates.subList(0, certificates.size() - 1));
      return stream(joined);
    }

    /**
     * The key for that chain.
     *
     * @return the private key block
     */
    public InputStream key() {
      return stream(privateKey);
    }

    /**
     * What a client certificate is verified against: the CA, and nothing else.
     *
     * <p>The last block in the bundle. A trust store that also held this plugin's own certificate
     * would accept it as a caller, and a plugin is called by the core and by nothing else.
     *
     * @return the CA block
     */
    public InputStream authority() {
      return stream(certificates.get(certificates.size() - 1));
    }

    private static InputStream stream(String pem) {
      return new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8));
    }
  }
}
