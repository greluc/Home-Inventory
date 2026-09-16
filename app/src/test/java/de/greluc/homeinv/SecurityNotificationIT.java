/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.homeinv.notification.api.SecurityNotifications;
import de.greluc.homeinv.notification.application.SecurityDeliveryDispatcher;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The queue for what the deployment owes an account (REQ-NOTI-004, ADR-0066).
 *
 * <p>Two properties are worth a test of their own, and both are about what happens when the mail
 * cannot go out. An installation with no channel plugin is the ordinary state of a fresh one, and a
 * security message must not be lost there — it must wait, visibly, with the reason recorded.
 *
 * <p>That it then reaches a real plugin with no tenant in the call is proved in {@code
 * PluginRuntimeIT}, where a plugin is actually running.
 */
@DisplayName("Account notifications")
class SecurityNotificationIT extends AbstractIntegrationTest {

  @Autowired private SecurityNotifications notifications;
  @Autowired private SecurityDeliveryDispatcher dispatcher;

  @Test
  @DisplayName("are queued without a tenant, and read back by account")
  void raisedAndReadBack() {
    UUID userId = UUID.randomUUID();

    // No TenantContext is opened anywhere in this test. That is the property:
    // the account may belong to no tenant, and the queue must not need one.
    SecurityNotifications.Queued queued =
        notifications.raise(
            new SecurityNotifications.NewSecurityNotification(
                userId,
                "security.password-changed",
                "somebody@example.org",
                "Your password was changed",
                "If this was not you, act now.",
                null,
                "en",
                "test-raise-" + userId));

    assertThat(queued.state()).isEqualTo("QUEUED");
    assertThat(queued.userId()).isEqualTo(userId);
    assertThat(notifications.of(userId, 10)).singleElement().isEqualTo(queued);
  }

  @Test
  @DisplayName("are raised once however often the same event is raised")
  void idempotent() {
    UUID userId = UUID.randomUUID();
    SecurityNotifications.NewSecurityNotification message =
        new SecurityNotifications.NewSecurityNotification(
            userId,
            "security.password-changed",
            "somebody@example.org",
            "Your password was changed",
            "If this was not you, act now.",
            null,
            "en",
            "test-idempotent-" + userId);

    SecurityNotifications.Queued first = notifications.raise(message);
    SecurityNotifications.Queued again = notifications.raise(message);

    assertThat(again.id()).isEqualTo(first.id());
    assertThat(notifications.of(userId, 10)).hasSize(1);
  }

  @Test
  @DisplayName("wait rather than die when no plugin serves the channel")
  void withoutAPlugin() {
    UUID userId = UUID.randomUUID();
    SecurityNotifications.Queued queued =
        notifications.raise(
            new SecurityNotifications.NewSecurityNotification(
                userId,
                "security.password-reset",
                "somebody@example.org",
                "Reset your password",
                "Open this link.",
                null,
                "en",
                "test-no-plugin-" + userId));

    int attempted = dispatcher.deliverDue(Instant.now());
    assertThat(attempted).isPositive();

    // Not a refusal by the far side — there is no far side. So it is retried:
    // an operator who grants the capability afterwards should find the queued
    // messages go out, rather than a pile of dead letters from before
    // (ADR-0066).
    SecurityNotifications.Queued after =
        notifications.of(userId, 10).stream()
            .filter(candidate -> candidate.id().equals(queued.id()))
            .findFirst()
            .orElseThrow();
    assertThat(after.state()).isEqualTo("QUEUED");
    assertThat(after.attempts()).isEqualTo(1);
    assertThat(after.nextAttemptAt()).isNotNull();

    // And the reason is on the record rather than only in a log line: "nothing
    // was sent" and "nothing was tried" have to be distinguishable.
    List<SecurityNotifications.Attempt> attempts = notifications.attempts(queued.id());
    assertThat(attempts).singleElement().satisfies(attempt -> {
      assertThat(attempt.outcome()).isEqualTo("FAILED");
      assertThat(attempt.detail()).contains("No plugin serves this channel for the instance");
    });
  }
}
