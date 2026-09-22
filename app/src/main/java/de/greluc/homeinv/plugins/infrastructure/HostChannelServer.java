/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.plugins.infrastructure;

import io.grpc.Grpc;
import io.grpc.Server;
import io.grpc.ServerCredentials;
import io.grpc.TlsServerCredentials;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The listener a plugin may call (ADR-0071).
 *
 * <h2>Its own port, and nothing else on it</h2>
 *
 * <p>Port 8090 still refuses every plugin segment (REQ-SEC-100) and is untouched by this. The host
 * channel is a <b>separate listener</b> on a separate port, serving one service with one method,
 * and a plugin segment is routed to it and to nothing else. Two listeners are two rules an
 * operator can read in {@code deploy/services.yaml}; one listener with a filter in front of it is a
 * rule nobody can see.
 *
 * <h2>Off unless it is configured</h2>
 *
 * <p>No identity file, no port, no server. An instance that runs no plugins — or runs only ones
 * that never ask the core for anything — listens on nothing extra, which is the right default for a
 * surface that exists to be reachable from somewhere less trusted.
 */
@Slf4j
@Component
public class HostChannelServer {

  private final Server server;

  /**
   * Starts the listener, when there is one to start.
   *
   * @param port where to listen, or 0 to run without the channel at all
   * @param identityFile the PEM bundle the core presents, the same one it uses when calling a
   *     plugin: one identity for the deployment's core, in both directions
   * @param endpoint the service
   * @param callers the interceptor that names the calling plugin from its certificate
   * @param plugins the trust manager that refuses a certificate no registration pins
   * @throws IOException when the port cannot be bound
   */
  public HostChannelServer(
      @Value("${HOMEINV_PLUGIN_HOST_PORT:0}") int port,
      @Value("${HOMEINV_MTLS_CORE_FILE:}") String identityFile,
      HostServicesEndpoint endpoint,
      CallerIdentity callers,
      RegisteredPlugins plugins)
      throws IOException {

    if (port <= 0 || identityFile == null || identityFile.isBlank()) {
      log.info(
          "The plugin host channel is off: no port or no mTLS identity is configured. Plugins can "
              + "be called and cannot call back.");
      this.server = null;
      return;
    }

    byte[] bundle = Files.readAllBytes(Path.of(identityFile));
    ServerCredentials credentials =
        TlsServerCredentials.newBuilder()
            .keyManager(new ByteArrayInputStream(bundle), new ByteArrayInputStream(bundle))
            // A registered plugin's certificate, by fingerprint -- not "anything
            // our CA signed", which would let a neighbour in (ADR-0044).
            .trustManager(plugins)
            .clientAuth(TlsServerCredentials.ClientAuth.REQUIRE)
            .build();

    this.server =
        Grpc.newServerBuilderForPort(port, credentials)
            .addService(endpoint)
            .intercept(callers)
            .build()
            .start();
    log.info("The plugin host channel is listening on {} (ADR-0071)", port);
  }

  /** Stops listening when the context goes. */
  @PreDestroy
  void stop() {
    if (server != null) {
      server.shutdown();
    }
  }

  /**
   * Where it is listening.
   *
   * @return the port, or 0 when the channel is off
   */
  public int port() {
    return server == null ? 0 : server.getPort();
  }
}
