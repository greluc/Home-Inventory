/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.plugins.infrastructure;

import de.greluc.homeinv.platform.PinnedCertificate;
import de.greluc.homeinv.plugin.api.PluginException;
import de.greluc.homeinv.plugins.application.PluginRuntimeProperties;
import io.grpc.ChannelCredentials;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.TlsChannelCredentials;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The channel to a plugin: mutually authenticated, certificate-pinned, one per plugin (REQ-PLG-002,
 * REQ-SEC-056).
 *
 * <h2>Pinned, not merely signed</h2>
 *
 * <p>The deployment's CA signs several identities — {@code api}, {@code worker}, {@code blobstore}
 * and every plugin. Trusting the CA alone would mean any of them could answer as any other: a
 * compromised plugin holding a valid client certificate could stand up a service on a segment and
 * receive the calls meant for its neighbour. Pinning the one certificate recorded in the
 * registration makes that a failed handshake rather than a silent redirection (ADR-0044).
 *
 * <p>The fingerprint comes from the operator's installed-plugin list and has no default. A plugin
 * registered without one is <b>not called</b> — not called insecurely, and not called with a
 * warning.
 *
 * <h2>One channel per plugin, kept</h2>
 *
 * <p>A channel is a connection pool with its own state, and building one per call would cost a TLS
 * handshake per call. They are closed when the context shuts down; a plugin whose registration
 * changes gets a new one, because the old one was built against the old fingerprint.
 */
@Slf4j
@Component
public class PluginChannels {

  private final PluginRuntimeProperties properties;
  private final Path identityFile;

  /** Keyed by plugin id and the connection it was built for, so a changed pin builds a new one. */
  private final Map<String, Entry> channels = new ConcurrentHashMap<>();

  /**
   * Reads this service's own mTLS identity.
   *
   * @param properties the operator's limits, for the message size the channel accepts
   * @param identityFile the PEM bundle this service presents, from {@code HOMEINV_MTLS_CORE_FILE}.
   *     The same identity the blob store already authenticates
   */
  public PluginChannels(
      PluginRuntimeProperties properties,
      @Value("${HOMEINV_MTLS_CORE_FILE:}") String identityFile) {
    this.properties = properties;
    this.identityFile =
        identityFile == null || identityFile.isBlank() ? null : Path.of(identityFile);
  }

  /**
   * The channel to one plugin, building it if there is none.
   *
   * @param pluginId which plugin, for the cache key and for the messages
   * @param endpoint {@code host:port} it listens on
   * @param fingerprint the SHA-256 of the certificate that may answer there, lowercase hex
   * @return the channel
   * @throws PluginException with {@link PluginException.Kind#DENIED} when there is no pin or no
   *     identity to present, and {@link PluginException.Kind#UNAVAILABLE} when the channel cannot
   *     be built. Never a channel without both: a client that fell back to an unauthenticated
   *     connection would work perfectly and would be talking to whatever answered
   */
  public ManagedChannel of(String pluginId, String endpoint, String fingerprint) {
    String pin = PinnedCertificate.normalise(fingerprint);
    if (pin.isEmpty()) {
      throw new PluginException(
          PluginException.Kind.DENIED,
          "Plugin "
              + pluginId
              + " has no certificate fingerprint in its registration. It pins the one certificate"
              + " that may answer as this plugin; without it any holder of a deployment"
              + " certificate could (REQ-SEC-056, ADR-0044).");
    }
    if (identityFile == null) {
      throw new PluginException(
          PluginException.Kind.DENIED,
          "HOMEINV_MTLS_CORE_FILE is not set. A plugin authenticates its caller and this service"
              + " has no identity to present.");
    }
    if (endpoint == null || endpoint.isBlank()) {
      throw new PluginException(
          PluginException.Kind.UNAVAILABLE,
          "Plugin " + pluginId + " has no endpoint in its registration; there is nowhere to call.");
    }

    Entry existing = channels.get(pluginId);
    if (existing != null && existing.matches(endpoint, pin)) {
      return existing.channel();
    }

    Entry built = new Entry(endpoint, pin, build(pluginId, endpoint, pin));
    Entry previous = channels.put(pluginId, built);
    if (previous != null) {
      // The registration changed under us. Shut the old one down rather than
      // leaking it: its pin is no longer the one the operator installed.
      previous.channel().shutdown();
    }
    return built.channel();
  }

  /**
   * Builds one channel.
   *
   * @param pluginId which plugin
   * @param endpoint where it listens
   * @param pin the normalised fingerprint
   * @return the channel
   */
  private ManagedChannel build(String pluginId, String endpoint, String pin) {
    byte[] bundle;
    try {
      bundle = Files.readAllBytes(identityFile);
    } catch (IOException unreadable) {
      throw new PluginException(
          PluginException.Kind.DENIED,
          "The mTLS identity at " + identityFile + " could not be read",
          unreadable);
    }

    try {
      ChannelCredentials credentials =
          TlsChannelCredentials.newBuilder()
              .keyManager(new ByteArrayInputStream(bundle), new ByteArrayInputStream(bundle))
              .trustManager(
                  new PinnedCertificate(
                      "Plugin " + pluginId, "the plugin's registered fingerprint", pin))
              .build();
      return Grpc.newChannelBuilder(endpoint, credentials)
          // Both directions. 09 §9.5 names 8 MiB, and gRPC's own 4 MiB default
          // is passed by a rendered label sheet or a scanned image.
          .maxInboundMessageSize(properties.getMaxMessageBytes())
          .build();
    } catch (IOException | RuntimeException unusable) {
      throw new PluginException(
          PluginException.Kind.UNAVAILABLE,
          "The channel to plugin " + pluginId + " at " + endpoint + " could not be built",
          unusable);
    }
  }

  /** Shuts every channel down when the context does. */
  @PreDestroy
  void close() {
    channels.values().forEach(entry -> entry.channel().shutdown());
    channels.values().forEach(entry -> awaitQuietly(entry.channel()));
    channels.clear();
  }

  /**
   * Waits a moment for a channel to finish, and gives up rather than holding shutdown.
   *
   * @param channel the channel
   */
  private static void awaitQuietly(ManagedChannel channel) {
    try {
      if (!channel.awaitTermination(2, TimeUnit.SECONDS)) {
        channel.shutdownNow();
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      channel.shutdownNow();
    }
  }

  /**
   * One cached channel and what it was built for.
   *
   * @param endpoint the address it points at
   * @param pin the fingerprint it trusts
   * @param channel the channel
   */
  private record Entry(String endpoint, String pin, ManagedChannel channel) {

    /**
     * Whether this channel is still the right one.
     *
     * @param wanted the endpoint now registered
     * @param wantedPin the fingerprint now registered
     * @return {@code true} when both still match
     */
    boolean matches(String wanted, String wantedPin) {
      return endpoint.equals(wanted) && pin.equals(wantedPin) && !channel.isShutdown();
    }
  }
}
