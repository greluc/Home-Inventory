/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.plugins.infrastructure;

import de.greluc.homeinv.platform.PinnedCertificate;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import javax.net.ssl.X509TrustManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Recognises a plugin by the certificate it presents (ADR-0071, REQ-SEC-056).
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>Every other mTLS check in this system pins <b>one</b> certificate: the core knows which plugin
 * it is calling and {@link PinnedCertificate} accepts that one and nothing else. The host channel
 * is the other direction — the core is answering, and it does not know which plugin is calling
 * until it looks. So this accepts a certificate whose SHA-256 is the fingerprint of <b>some
 * registered plugin</b>, and says which.
 *
 * <p>It is deliberately not "any certificate our CA signed". A certificate the deployment's own CA
 * issued is a valid member of the deployment and is still not necessarily a plugin — the same
 * distinction {@code PluginRuntimeIT} already holds for the outbound direction, and the reason
 * trusting the CA alone would let a neighbour through.
 *
 * <p>Nothing here decides what the caller may <b>do</b>. Recognising a plugin is not authorising
 * one: the capability grant is checked per call, per tenant, after the connection is up
 * (REQ-PLG-005). Reachability is never authorisation (ADR-0044).
 */
@Component
@RequiredArgsConstructor
public class RegisteredPlugins implements X509TrustManager {

  /** How many registrations are considered. The cap REQ-NFR-010 puts on every collection. */
  private static final int MAX = 200;

  private final PluginRegistryQueries registrations;

  /**
   * Which plugin presented this certificate, if any did.
   *
   * @param certificate the peer's certificate
   * @return the plugin id, or empty when no registration pins that fingerprint
   */
  public Optional<String> identify(X509Certificate certificate) {
    String presented = fingerprintOf(certificate);
    return registrations.installed(MAX).stream()
        .filter(registration -> !registration.disabled())
        .map(registration -> registration.pluginId())
        .filter(
            pluginId ->
                registrations
                    .connection(pluginId)
                    .map(connection -> PinnedCertificate.normalise(connection.fingerprint()))
                    .filter(pin -> !pin.isBlank())
                    .map(presented::equals)
                    .orElse(false))
        .findFirst();
  }

  @Override
  public void checkClientTrusted(X509Certificate[] chain, String authType)
      throws CertificateException {
    if (chain == null || chain.length == 0) {
      throw new CertificateException("A caller on the host channel presented no certificate");
    }
    if (identify(chain[0]).isEmpty()) {
      // Refused before a method is dispatched, which is where it belongs: an
      // unknown certificate is not a call that fails, it is a connection that
      // does not happen.
      throw new CertificateException(
          "The certificate presented on the host channel is not the registered certificate of any "
              + "installed plugin");
    }
  }

  @Override
  public void checkServerTrusted(X509Certificate[] chain, String authType)
      throws CertificateException {
    // This manager is only ever a server's view of its clients. A core that used
    // it to check a server would be trusting whatever answered, so it says no.
    throw new CertificateException(
        "This trust manager recognises plugins calling in, and never a server answering");
  }

  @Override
  public X509Certificate[] getAcceptedIssuers() {
    // Deliberately empty. The chain is not what decides here -- the fingerprint
    // is -- and returning issuers would invite a caller to think otherwise.
    return new X509Certificate[0];
  }

  /**
   * A certificate's SHA-256, spelled the way a registration stores one.
   *
   * @param certificate the certificate
   * @return the fingerprint, lower case and without colons
   */
  static String fingerprintOf(X509Certificate certificate) {
    try {
      MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(sha256.digest(certificate.getEncoded()));
    } catch (NoSuchAlgorithmException | CertificateEncodingException impossible) {
      throw new IllegalStateException("A certificate could not be fingerprinted", impossible);
    }
  }

  /**
   * Every plugin id the registry knows, for a caller that wants to look one up itself.
   *
   * @return the ids
   */
  List<String> installed() {
    return registrations.installed(MAX).stream()
        .map(registration -> registration.pluginId())
        .toList();
  }
}
