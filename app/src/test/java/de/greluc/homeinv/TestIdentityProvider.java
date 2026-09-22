/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import de.greluc.homeinv.plugin.v1.IdentityProviderBeginRequest;
import de.greluc.homeinv.plugin.v1.IdentityProviderBeginResponse;
import de.greluc.homeinv.plugin.v1.IdentityProviderCompleteRequest;
import de.greluc.homeinv.plugin.v1.IdentityProviderDescribeRequest;
import de.greluc.homeinv.plugin.v1.IdentityProviderDescriptor;
import de.greluc.homeinv.plugin.v1.IdentityProviderGrpc;
import de.greluc.homeinv.plugin.v1.IdentityProviderIdentity;
import de.greluc.homeinv.plugins.application.DefaultPluginRegistry;
import io.grpc.Grpc;
import io.grpc.Server;
import io.grpc.ServerCredentials;
import io.grpc.TlsServerCredentials;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A provider that signs one person in, over a real TLS socket.
 *
 * <p>The same shape {@link DocumentRenderIT} and {@code TenantBlobStoreIT} use for their ports: the
 * thing under test is the contract between the core and a plugin, and a mocked stub would assert
 * the mock. What it does <b>not</b> do is verify a token — that is the plugin's half, and
 * {@code plugins/oidc} has its own tests for it. What matters here is that the core sends the state,
 * the nonce and the challenge it minted, and believes only what comes back through this port.
 *
 * <p>Shared by the two federated tests, which differ only in the registration mode their instance
 * runs with.
 */
final class TestIdentityProvider {

  /** The plugin id both tests install it under. */
  static final String PLUGIN_ID = "de.greluc.homeinv.plugin.test.oidc";

  /** The issuer it claims to be. */
  static final String ISSUER = "https://provider.example/realm";

  private static final AtomicReference<Server> SERVER = new AtomicReference<>();
  private static final AtomicReference<TestPki.Identity> IDENTITY = new AtomicReference<>();

  /** What the next {@code Complete} answers with. */
  private static final AtomicReference<IdentityProviderIdentity> NEXT = new AtomicReference<>();

  /** What the last {@code Begin} was asked to start, so a test can read the state back. */
  private static final AtomicReference<IdentityProviderBeginRequest> LAST_BEGIN =
      new AtomicReference<>();

  private TestIdentityProvider() {}

  /**
   * Starts the provider once for the JVM and returns its port.
   *
   * @param pki the authority both sides trust
   * @return the port it listens on
   * @throws Exception when the socket cannot be opened
   */
  static int start(TestPki pki) throws Exception {
    Server running = SERVER.get();
    if (running != null) {
      return running.getPort();
    }
    if (IDENTITY.get() == null) {
      IDENTITY.set(pki.issue("localhost"));
    }
    byte[] bundle = Files.readAllBytes(IDENTITY.get().bundle());
    ServerCredentials credentials =
        TlsServerCredentials.newBuilder()
            .keyManager(new ByteArrayInputStream(bundle), new ByteArrayInputStream(bundle))
            .trustManager(new ByteArrayInputStream(pki.caPem().getBytes(StandardCharsets.UTF_8)))
            .clientAuth(TlsServerCredentials.ClientAuth.REQUIRE)
            .build();
    Server server =
        Grpc.newServerBuilderForPort(0, credentials).addService(new Service()).build().start();
    SERVER.set(server);
    return server.getPort();
  }

  /**
   * Installs it for the instance, which is the grant a sign-in resolves through (ADR-0066).
   *
   * @param registry the plugin registry
   * @param pki the authority both sides trust
   * @throws Exception when it cannot be started or registered
   */
  static void install(DefaultPluginRegistry registry, TestPki pki) throws Exception {
    int port = start(pki);
    registry.register(
        manifest().getBytes(StandardCharsets.UTF_8),
        "localhost:" + port,
        IDENTITY.get().fingerprint(),
        AbstractIntegrationTest.UNSIGNED_FIXTURE, true);
    // For the INSTANCE and not for a tenant: a sign-in happens before any tenant
    // is known, so a per-tenant grant would have nothing to key on.
    registry.grantForInstance(PLUGIN_ID, "network:outbound", UUID.randomUUID());
  }

  /**
   * What the next completed flow reports.
   *
   * @param subject the provider's own id for the person
   * @param email the address, or empty
   * @param emailVerified whether the provider says it verified it
   */
  static void willReport(String subject, String email, boolean emailVerified) {
    NEXT.set(
        IdentityProviderIdentity.newBuilder()
            .setSubject(subject)
            .setIssuer(ISSUER)
            .setEmail(email)
            .setEmailVerified(emailVerified)
            .setDisplayName("Ada Lovelace")
            .build());
  }

  /**
   * What the core asked the provider to start.
   *
   * @return the last begin request
   */
  static IdentityProviderBeginRequest lastBegin() {
    return LAST_BEGIN.get();
  }

  /** Stops it, at the end of a class. */
  static void stop() {
    Server running = SERVER.getAndSet(null);
    if (running != null) {
      running.shutdownNow();
    }
    NEXT.set(null);
    LAST_BEGIN.set(null);
  }

  /** The gRPC service itself. */
  private static final class Service extends IdentityProviderGrpc.IdentityProviderImplBase {

    @Override
    public void describe(
        IdentityProviderDescribeRequest request,
        StreamObserver<IdentityProviderDescriptor> responses) {
      responses.onNext(
          IdentityProviderDescriptor.newBuilder()
              .setProviderKey("oidc-test")
              .setDisplayName("The test provider")
              .setPkceRequired(true)
              .build());
      responses.onCompleted();
    }

    @Override
    public void begin(
        IdentityProviderBeginRequest request,
        StreamObserver<IdentityProviderBeginResponse> responses) {
      LAST_BEGIN.set(request);
      // The parameters go on the URL the way a provider would expect them, so a
      // test can assert that the core minted them rather than the plugin.
      responses.onNext(
          IdentityProviderBeginResponse.newBuilder()
              .setAuthorizationUrl(
                  ISSUER
                      + "/authorize?response_type=code&state="
                      + request.getState()
                      + "&nonce="
                      + request.getNonce()
                      + "&code_challenge="
                      + request.getCodeChallenge()
                      + "&code_challenge_method=S256&redirect_uri="
                      + request.getRedirectUri())
              .build());
      responses.onCompleted();
    }

    @Override
    public void complete(
        IdentityProviderCompleteRequest request,
        StreamObserver<IdentityProviderIdentity> responses) {
      IdentityProviderIdentity identity = NEXT.get();
      if (identity == null) {
        responses.onError(
            io.grpc.Status.UNAUTHENTICATED
                .withDescription("no identity was prepared for this test")
                .asRuntimeException());
        return;
      }
      responses.onNext(identity);
      responses.onCompleted();
    }
  }

  /** The manifest the core reads at registration. */
  static String manifest() {
    return """
        apiVersion: home-inv.plugin/v1
        metadata:
          id: %s
          name: "A provider that signs one person in"
          version: "1.0.0"
          vendor: "greluc"
          license: "Apache-2.0"
        spec:
          contract: ">=1.0.0 <2.0.0"
          runtime: out-of-process
          implements:
            - port: IdentityProvider
              priority: 100
          capabilities:
            - id: network:outbound
              hosts: ["provider.example"]
              reason: "Discovery, the token endpoint and the key set"
        """
        .formatted(PLUGIN_ID);
  }
}
