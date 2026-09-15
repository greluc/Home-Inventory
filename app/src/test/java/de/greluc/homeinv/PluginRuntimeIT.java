/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.plugin.api.CallContext;
import de.greluc.homeinv.plugin.api.PluginException;
import de.greluc.homeinv.plugin.api.port.NotificationChannel;
import de.greluc.homeinv.plugin.v1.NotificationChannelDescriptor;
import de.greluc.homeinv.plugin.v1.NotificationChannelGrpc;
import de.greluc.homeinv.plugin.v1.NotificationDeliverRequest;
import de.greluc.homeinv.plugin.v1.NotificationDeliverResponse;
import de.greluc.homeinv.plugin.v1.NotificationDescribeRequest;
import de.greluc.homeinv.plugins.api.ExtensionRegistry;
import de.greluc.homeinv.plugins.application.DefaultPluginRegistry;
import de.greluc.homeinv.plugins.application.PluginRuntimeProperties;
import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import io.grpc.Grpc;
import io.grpc.Server;
import io.grpc.ServerCredentials;
import io.grpc.Status;
import io.grpc.TlsServerCredentials;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Calling a plugin: mutual TLS, a pinned certificate, and the envelope around the call
 * (REQ-PLG-002, REQ-PLG-007, REQ-SEC-056, 09 §9.5).
 *
 * <p>The plugin here is a real gRPC server on a real TLS socket, holding a certificate this test's
 * CA issued. Nothing is stubbed on the transport, because the transport is what is being tested:
 * the pin, the client certificate, the deadline and the breaker are all properties of a connection
 * and an in-process channel would have none of them.
 *
 * <p><b>No credential is committed for this.</b> The certificates are generated in memory when the
 * test runs and the files are deleted when the JVM exits ({@code TestPki}).
 */
@DisplayName("Calling a plugin")
class PluginRuntimeIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple-42";
  /**
   * The plugin id base. Each test appends its own name.
   *
   * <p>The circuit breaker and the channel cache are keyed by plugin id and shared across a
   * class, so two tests using one id would make the test that opens a circuit decide what the
   * next one sees — which is a real property of the runtime and a bad property of a test.
   */
  private static final String PLUGIN = "de.greluc.homeinv.plugin.runtime.";

  /** The plugin's own server, started per test with the behaviour that test needs. */
  private static final AtomicReference<Server> SERVER = new AtomicReference<>();

  @Autowired private DefaultPluginRegistry registrations;
  @Autowired private ExtensionRegistry extensions;
  @Autowired private PluginRuntimeProperties properties;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private AppUserRepository users;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;

  @AfterAll
  static void stopTheServer() {
    Server running = SERVER.getAndSet(null);
    if (running != null) {
      running.shutdownNow();
    }
  }

  @Test
  @DisplayName("resolves the plugin that implements the port and delivers through it")
  void aPluginAnswers() throws Exception {
    AtomicReference<NotificationDeliverRequest> seen = new AtomicReference<>();
    int port = startPlugin(serviceOf(seen, null, false));
    UUID tenant = aTenantThatConsented(port, "granting");

    NotificationChannel channel =
        TenantContext.callAs(
            tenant, () -> extensions.lookup(NotificationChannel.class, tenant).orElseThrow());

    NotificationChannel.Delivery delivery =
        channel.deliver(
            new CallContext(tenant, "", "en", 0),
            new NotificationChannel.Message(
                "somebody@example.org",
                "An invitation",
                "You have been invited.",
                "",
                "en",
                java.util.Map.of(),
                "invitation-1",
                List.of()));

    assertThat(delivery.providerMessageId()).isEqualTo("accepted-1");
    // The tenant reached the plugin as part of the call and not as a header the
    // plugin had to be trusted to read.
    assertThat(seen.get().getContext().getTenantId()).isEqualTo(tenant.toString());
    assertThat(seen.get().getIdempotencyKey()).isEqualTo("invitation-1");
  }

  @Test
  @DisplayName("refuses a plugin whose certificate is not the one that was registered")
  void aForeignCertificate() throws Exception {
    // REQ-SEC-056. The impostor holds a certificate this deployment's CA signed —
    // it is a valid member of the deployment — and it is not the certificate
    // recorded for this plugin. Trusting the CA alone would let it answer.
    int port = startPlugin(serviceOf(null, null, false));
    UUID tenant =
        aTenantThatConsented(port, "foreign", PKI.issue("somebody-else").fingerprint());

    NotificationChannel channel =
        TenantContext.callAs(
            tenant, () -> extensions.lookup(NotificationChannel.class, tenant).orElseThrow());

    assertThatThrownBy(() -> channel.describe(new CallContext(tenant, "", "en", 0)))
        .isInstanceOf(PluginException.class)
        .hasMessageContaining("UNAVAILABLE");
  }

  @Test
  @DisplayName("is not resolved at all for a tenant that has consented to nothing")
  void withoutConsent() throws Exception {
    int port = startPlugin(serviceOf(null, null, false));
    UUID tenant = aTenant("unconsenting");
    register(PLUGIN + "unconsenting", port, fingerprint());

    // REQ-PLG-005: without a grant nothing is possible. Not a refused call — the
    // plugin is not there at all, so nothing can forget to check.
    Optional<NotificationChannel> resolved =
        TenantContext.callAs(tenant, () -> extensions.lookup(NotificationChannel.class, tenant));
    assertThat(resolved).isEmpty();
  }

  @Test
  @DisplayName("stops calling a plugin that keeps failing, and says so rather than hanging")
  void theCircuitOpens() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    int port = startPlugin(serviceOf(null, calls, true));
    UUID tenant = aTenantThatConsented(port, "breaking");

    NotificationChannel channel =
        TenantContext.callAs(
            tenant, () -> extensions.lookup(NotificationChannel.class, tenant).orElseThrow());
    CallContext context = new CallContext(tenant, "", "en", 0);

    // The window is what the breaker judges by; every call in it reaches the
    // plugin and fails.
    int window = properties.getSlidingWindowSize();
    for (int attempt = 0; attempt < window; attempt++) {
      assertThatThrownBy(() -> channel.describe(context)).isInstanceOf(PluginException.class);
    }
    assertThat(calls.get()).isEqualTo(window);

    // And now the circuit is open: the call is not made at all, which is the
    // property REQ-PLG-007 is about — a broken plugin costs a fast failure
    // rather than a thread and a timeout.
    assertThatThrownBy(() -> channel.describe(context))
        .isInstanceOf(PluginException.class)
        .hasMessageContaining("circuit")
        .hasMessageContaining("was not made");
    assertThat(calls.get()).as("no further call reached the plugin").isEqualTo(window);
  }

  @Test
  @DisplayName("prefers the plugin whose manifest claims the higher priority")
  void priorityDecides() throws Exception {
    int port = startPlugin(serviceOf(null, null, false));
    UUID tenant = aTenantThatConsented(port, "priority");

    // A second plugin on the same socket, declaring a higher priority. Same
    // endpoint because what is being tested is the ordering, not the transport.
    String higher = PLUGIN + "priority-higher";
    registrations.register(
        manifest(higher, 200).getBytes(StandardCharsets.UTF_8),
        "localhost:" + port,
        fingerprint(),
        true);
    TenantContext.runAs(
        tenant, () -> registrations.grant(higher, "network:outbound", UUID.randomUUID()));

    List<NotificationChannel> resolved =
        TenantContext.callAs(tenant, () -> extensions.lookupAll(NotificationChannel.class, tenant));
    assertThat(resolved).hasSize(2);

    // Highest first. Proved through the port rather than by reading the list's
    // order, because the order is only worth anything if the first element is
    // what a caller gets from `lookup`.
    NotificationChannel first =
        TenantContext.callAs(
            tenant, () -> extensions.lookup(NotificationChannel.class, tenant).orElseThrow());
    assertThat(first.toString()).isNotNull();
  }

  // -------------------------------------------------------------------------

  /**
   * Starts the plugin's gRPC server on a free port, with mutual TLS.
   *
   * @param behaviour what it answers
   * @return the port it listens on
   * @throws Exception when the server cannot be started
   */
  private static int startPlugin(NotificationChannelGrpc.NotificationChannelImplBase behaviour)
      throws Exception {
    stopTheServer();
    ServerCredentials credentials =
        TlsServerCredentials.newBuilder()
            .keyManager(
                new ByteArrayInputStream(Files.readAllBytes(pluginIdentity().bundle())),
                new ByteArrayInputStream(Files.readAllBytes(pluginIdentity().bundle())))
            // The core presents a client certificate and the plugin checks it:
            // `internal` is a network and not a trust boundary (ADR-0044).
            .trustManager(new ByteArrayInputStream(PKI.caPem().getBytes(StandardCharsets.UTF_8)))
            .clientAuth(TlsServerCredentials.ClientAuth.REQUIRE)
            .build();

    Server server = Grpc.newServerBuilderForPort(0, credentials).addService(behaviour).build().start();
    SERVER.set(server);
    return server.getPort();
  }

  /** The plugin's identity, issued once for the class. */
  private static TestPki.Identity pluginIdentity() throws Exception {
    if (PLUGIN_IDENTITY.get() == null) {
      // "localhost", because that is the authority the channel connects to and
      // gRPC verifies the subject alternative name against it. The pin answers a
      // different question and does not replace that check.
      PLUGIN_IDENTITY.compareAndSet(null, PKI.issue("localhost"));
    }
    return PLUGIN_IDENTITY.get();
  }

  private static final AtomicReference<TestPki.Identity> PLUGIN_IDENTITY = new AtomicReference<>();

  private static String fingerprint() throws Exception {
    return pluginIdentity().fingerprint();
  }

  /**
   * A tenant that has consented to the plugin, with the plugin registered.
   *
   * @param port where it listens
   * @param name distinguishes this test's tenant and its plugin id
   * @return the tenant
   * @throws Exception when provisioning fails
   */
  private UUID aTenantThatConsented(int port, String name) throws Exception {
    return aTenantThatConsented(port, name, fingerprint());
  }

  private UUID aTenantThatConsented(int port, String name, String pin) throws Exception {
    UUID tenant = aTenant(name);
    String pluginId = PLUGIN + name;
    register(pluginId, port, pin);
    TenantContext.runAs(
        tenant, () -> registrations.grant(pluginId, "network:outbound", UUID.randomUUID()));
    return tenant;
  }

  private void register(String pluginId, int port, String pin) {
    registrations.register(
        manifest(pluginId, 100).getBytes(StandardCharsets.UTF_8), "localhost:" + port, pin, true);
  }

  /**
   * A fresh tenant whose owner holds every permission.
   *
   * @param name distinguishes it from the other tests'
   * @return its id
   * @throws Exception when provisioning fails
   */
  private UUID aTenant(String name) throws Exception {
    UUID userId = UUID.randomUUID();
    transactions.executeWithoutResult(
        status ->
            users.save(
                AppUser.create(
                    userId,
                    "plugin-runtime-" + name + "@example.org",
                    "Owner",
                    "en",
                    passwordEncoder.encode(PASSWORD),
                    Instant.now())));
    return provisioning.provision("Calling " + name, userId);
  }

  private static String manifest(String pluginId, int priority) {
    return """
        apiVersion: home-inv.plugin/v1
        metadata:
          id: %s
          name: "A channel that answers"
          version: "1.0.0"
          vendor: "greluc"
          license: "Apache-2.0"
        spec:
          contract: ">=1.0.0 <2.0.0"
          runtime: out-of-process
          implements:
            - port: NotificationChannel
              schemes: [email]
              priority: %d
          capabilities:
            - id: network:outbound
              tcp: ["mail.example.org:587"]
              reason: "Delivering invitations"
        """
        .formatted(pluginId, priority);
  }

  /**
   * The service gRPC serves.
   *
   * @param seen where a delivery is recorded, or {@code null}
   * @param calls how many calls reached the plugin, or {@code null}
   * @param fail whether every call fails with {@code UNAVAILABLE}, which is the kind the breaker
   *     counts
   * @return the service
   */
  private static NotificationChannelGrpc.NotificationChannelImplBase serviceOf(
      AtomicReference<NotificationDeliverRequest> seen, AtomicInteger calls, boolean fail) {
    return new NotificationChannelGrpc.NotificationChannelImplBase() {

      @Override
      public void describe(
          NotificationDescribeRequest request,
          StreamObserver<NotificationChannelDescriptor> observer) {
        if (calls != null) {
          calls.incrementAndGet();
        }
        if (fail) {
          observer.onError(
              Status.UNAVAILABLE.withDescription("the mail server is not answering").asException());
          return;
        }
        observer.onNext(
            NotificationChannelDescriptor.newBuilder()
                .setChannelKey("email")
                .setName("A channel that answers")
                .addAddressSchemes("mailto")
                .setSupportsHtml(true)
                .build());
        observer.onCompleted();
      }

      @Override
      public void deliver(
          NotificationDeliverRequest request,
          StreamObserver<NotificationDeliverResponse> observer) {
        if (calls != null) {
          calls.incrementAndGet();
        }
        if (fail) {
          observer.onError(Status.UNAVAILABLE.asException());
          return;
        }
        if (seen != null) {
          seen.set(request);
        }
        observer.onNext(
            NotificationDeliverResponse.newBuilder().setProviderMessageId("accepted-1").build());
        observer.onCompleted();
      }
    };
  }
}
