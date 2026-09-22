/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.homeinv.notification.api.Notifications;
import de.greluc.homeinv.notification.application.DeliveryDispatcher;
import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.plugin.v1.NotificationChannelDescriptor;
import de.greluc.homeinv.plugin.v1.NotificationChannelGrpc;
import de.greluc.homeinv.plugin.v1.NotificationDeliverRequest;
import de.greluc.homeinv.plugin.v1.NotificationDeliverResponse;
import de.greluc.homeinv.plugin.v1.NotificationDescribeRequest;
import de.greluc.homeinv.plugins.application.DefaultPluginRegistry;
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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Delivering a notification through a plugin, and what happens when it does not work
 * (REQ-NOTI-002, REQ-NOTI-005, REQ-NOTI-006).
 *
 * <p>There is no in-core channel and there cannot be one: every channel talks to a host outside the
 * deployment, and the core has no outbound route (ADR-0026). So the plugin here is a real gRPC
 * server on a real TLS socket, and the certificates are generated when the test runs.
 *
 * <p>The three outcomes are what the block is for. A channel that accepts ends the notification; one
 * that is not answering leaves it queued with a later attempt; one that says "that is not an
 * address" ends it immediately, because repeating it would not make it one.
 */
@DisplayName("Delivering a notification")
class NotificationDeliveryIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple-42";
  private static final String PLUGIN = "de.greluc.homeinv.plugin.notify.";
  private static final String KIND = "invitation";

  private static final AtomicReference<Server> SERVER = new AtomicReference<>();
  private static final AtomicReference<TestPki.Identity> IDENTITY = new AtomicReference<>();

  @Autowired private Notifications notifications;
  @Autowired private DeliveryDispatcher dispatcher;
  @Autowired private DefaultPluginRegistry registrations;
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
  @DisplayName("queues nothing for somebody who asked for nothing")
  void withoutASubscription() throws Exception {
    Tenant tenant = aTenant("unsubscribed");

    List<Notifications.QueuedNotification> queued =
        TenantContext.callAs(
            tenant.id(),
            () -> transactions.execute(status -> notifications.raise(aMessage(tenant.userId()))));

    // REQ-NOTI-006. Not a failure: somebody who asked for nothing gets nothing,
    // and the caller learns that by getting an empty list.
    assertThat(queued).isEmpty();
  }

  @Test
  @DisplayName("delivers through the plugin and records that it was accepted")
  void aChannelAccepts() throws Exception {
    int port = startPlugin(serviceOf(false, null));
    Tenant tenant = aTenantWithAPlugin("accepted", port);
    subscribe(tenant);

    Notifications.QueuedNotification queued = raiseOne(tenant);
    assertThat(queued.state()).isEqualTo("QUEUED");

    assertThat(dispatcher.deliverDue(databaseNow())).isGreaterThanOrEqualTo(1);

    assertThat(stateOf(tenant, queued.id())).isEqualTo("DELIVERED");
    List<Notifications.DeliveryAttempt> attempts = attemptsOf(tenant, queued.id());
    assertThat(attempts).hasSize(1);
    assertThat(attempts.getFirst().outcome()).isEqualTo("ACCEPTED");
  }

  @Test
  @DisplayName("leaves it queued and tries later when the far side is not answering")
  void aChannelIsUnavailable() throws Exception {
    int port = startPlugin(serviceOf(true, Status.UNAVAILABLE));
    Tenant tenant = aTenantWithAPlugin("unavailable", port);
    subscribe(tenant);

    Notifications.QueuedNotification queued = raiseOne(tenant);
    dispatcher.deliverDue(databaseNow());

    // Still outstanding, and the attempt is on the record. A mail server that is
    // down is down for minutes, not forever.
    assertThat(stateOf(tenant, queued.id())).isEqualTo("QUEUED");
    List<Notifications.DeliveryAttempt> attempts = attemptsOf(tenant, queued.id());
    assertThat(attempts).hasSize(1);
    assertThat(attempts.getFirst().outcome()).isEqualTo("FAILED");

    // And the next attempt is in the future rather than immediately, so a broken
    // channel is not hammered.
    assertThat(dispatcher.deliverDue(databaseNow())).isZero();
  }

  @Test
  @DisplayName("gives up at once when the channel says that is not an address")
  void aChannelRefuses() throws Exception {
    int port = startPlugin(serviceOf(true, Status.INVALID_ARGUMENT));
    Tenant tenant = aTenantWithAPlugin("refused", port);
    subscribe(tenant);

    Notifications.QueuedNotification queued = raiseOne(tenant);
    dispatcher.deliverDue(databaseNow());

    // Dead-lettered on the first attempt. Repeating a malformed address a
    // hundred times does not make it valid, and the difference between this and
    // the case above is the difference between a retry queue and a loop.
    assertThat(stateOf(tenant, queued.id())).isEqualTo("DEAD_LETTERED");
    List<Notifications.DeliveryAttempt> attempts = attemptsOf(tenant, queued.id());
    assertThat(attempts).hasSize(1);
    assertThat(attempts.getFirst().outcome()).isEqualTo("REFUSED");
    // The notification stays readable with its history beside it, which is what
    // makes "what happened to my invitation" answerable (REQ-NOTI-005).
    assertThat(attempts.getFirst().detail()).isNotBlank();
  }

  // -------------------------------------------------------------------------

  private Notifications.QueuedNotification raiseOne(Tenant tenant) {
    List<Notifications.QueuedNotification> queued =
        TenantContext.callAs(
            tenant.id(),
            () -> transactions.execute(status -> notifications.raise(aMessage(tenant.userId()))));
    assertThat(queued).hasSize(1);
    return queued.getFirst();
  }

  private static Notifications.NewNotification aMessage(UUID userId) {
    return new Notifications.NewNotification(
        userId,
        KIND,
        "You have been invited",
        "Somebody invited you to a household.",
        null,
        "en",
        "invitation-" + userId);
  }

  private void subscribe(Tenant tenant) {
    TenantContext.runAs(
        tenant.id(),
        () ->
            transactions.executeWithoutResult(
                status ->
                    notifications.subscribe(
                        tenant.userId(), KIND, "email", "somebody@example.org", true)));
  }

  private String stateOf(Tenant tenant, UUID notificationId) {
    return TenantContext.callAs(
        tenant.id(),
        () ->
            transactions.execute(
                status ->
                    notifications
                        .notification(notificationId)
                        .map(Notifications.QueuedNotification::state)
                        .orElseThrow()));
  }

  private List<Notifications.DeliveryAttempt> attemptsOf(Tenant tenant, UUID notificationId) {
    return TenantContext.callAs(
        tenant.id(),
        () -> transactions.execute(status -> notifications.attempts(notificationId)));
  }

  /**
   * A tenant whose administrator exists.
   *
   * @param name distinguishes this test's tenant
   * @return the tenant and its owner
   * @throws Exception when provisioning fails
   */
  private Tenant aTenant(String name) throws Exception {
    UUID userId = UUID.randomUUID();
    transactions.executeWithoutResult(
        status ->
            users.save(
                AppUser.create(
                    userId,
                    "notify-" + name + "@example.org",
                    "Owner",
                    "en",
                    passwordEncoder.encode(PASSWORD),
                    Instant.now())));
    return new Tenant(provisioning.provision("Notifying " + name, userId), userId);
  }

  /**
   * The same, with a notification plugin registered and consented to.
   *
   * @param name distinguishes this test's tenant and plugin
   * @param port where the plugin listens
   * @return the tenant
   * @throws Exception when provisioning fails
   */
  private Tenant aTenantWithAPlugin(String name, int port) throws Exception {
    Tenant tenant = aTenant(name);
    String pluginId = PLUGIN + name;
    registrations.register(
        manifest(pluginId).getBytes(StandardCharsets.UTF_8),
        "localhost:" + port,
        identity().fingerprint(),
        UNSIGNED_FIXTURE, true);
    TenantContext.runAs(
        tenant.id(),
        () -> registrations.grant(pluginId, "network:outbound", tenant.userId()));
    return tenant;
  }

  private static String manifest(String pluginId) {
    return """
        apiVersion: home-inv.plugin/v1
        metadata:
          id: %s
          name: "A channel"
          version: "1.0.0"
          vendor: "greluc"
          license: "Apache-2.0"
        spec:
          contract: ">=1.0.0 <2.0.0"
          runtime: out-of-process
          implements:
            - port: NotificationChannel
              schemes: [email]
              priority: 100
          capabilities:
            - id: network:outbound
              tcp: ["mail.example.org:587"]
              reason: "Delivering invitations"
        """
        .formatted(pluginId);
  }

  private static TestPki.Identity identity() throws Exception {
    if (IDENTITY.get() == null) {
      IDENTITY.compareAndSet(null, PKI.issue("localhost"));
    }
    return IDENTITY.get();
  }

  private static int startPlugin(NotificationChannelGrpc.NotificationChannelImplBase behaviour)
      throws Exception {
    stopTheServer();
    ServerCredentials credentials =
        TlsServerCredentials.newBuilder()
            .keyManager(
                new ByteArrayInputStream(Files.readAllBytes(identity().bundle())),
                new ByteArrayInputStream(Files.readAllBytes(identity().bundle())))
            .trustManager(new ByteArrayInputStream(PKI.caPem().getBytes(StandardCharsets.UTF_8)))
            .clientAuth(TlsServerCredentials.ClientAuth.REQUIRE)
            .build();
    Server server =
        Grpc.newServerBuilderForPort(0, credentials).addService(behaviour).build().start();
    SERVER.set(server);
    return server.getPort();
  }

  /**
   * The channel the plugin serves.
   *
   * @param fail whether every delivery fails
   * @param status what it fails with — the difference between "not answering" and "that is not an
   *     address", which is what decides whether the notification is retried
   * @return the service
   */
  private static NotificationChannelGrpc.NotificationChannelImplBase serviceOf(
      boolean fail, Status status) {
    return new NotificationChannelGrpc.NotificationChannelImplBase() {

      @Override
      public void describe(
          NotificationDescribeRequest request,
          StreamObserver<NotificationChannelDescriptor> observer) {
        observer.onNext(
            NotificationChannelDescriptor.newBuilder()
                .setChannelKey("email")
                .setName("A channel")
                .addAddressSchemes("mailto")
                .build());
        observer.onCompleted();
      }

      @Override
      public void deliver(
          NotificationDeliverRequest request,
          StreamObserver<NotificationDeliverResponse> observer) {
        if (fail) {
          observer.onError(status.withDescription("the channel said so").asException());
          return;
        }
        observer.onNext(
            NotificationDeliverResponse.newBuilder().setProviderMessageId("accepted-1").build());
        observer.onCompleted();
      }
    };
  }

  /**
   * A tenant and the person who owns it.
   *
   * @param id the tenant
   * @param userId its owner
   */
  private record Tenant(UUID id, UUID userId) {}
}
