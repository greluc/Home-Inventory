/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.notification.application;

import de.greluc.homeinv.notification.infrastructure.SecurityNotificationQueries;
import de.greluc.homeinv.plugin.api.CallContext;
import de.greluc.homeinv.plugin.api.PluginException;
import de.greluc.homeinv.plugin.api.port.NotificationChannel;
import de.greluc.homeinv.plugins.api.ExtensionRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tries one account notification once (REQ-NOTI-004, REQ-NOTI-005, ADR-0066).
 *
 * <p>The same shape as {@link DeliveryRunner} — one attempt, recorded either way, widening retries,
 * then a dead letter — with two differences, and both of them are the reason this class exists
 * rather than a flag on that one:
 *
 * <ul>
 *   <li><b>No tenant anywhere.</b> The channel is resolved from the <b>instance</b> grants and the
 *       call carries {@link CallContext#forInstance}, so the plugin receives an empty tenant and a
 *       scope saying why.
 *   <li><b>No plugin is a louder failure.</b> On the tenant path a missing channel means an
 *       invitation waits for the operator to install one. Here it means a person asked to get back
 *       into their account and nothing went out, so it is logged at warning on every attempt rather
 *       than at debug.
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SecurityDeliveryRunner {

  /** How many notifications one run takes, so a backlog does not become one long transaction. */
  static final int BATCH = 50;

  private final SecurityNotificationQueries queries;
  private final ExtensionRegistry extensions;

  /**
   * How many tries a notification gets before it is dead-lettered.
   *
   * <p>The same five the tenant queue uses. There is no argument for being more patient with a
   * message whose value decays fastest.
   */
  @Value("${homeinv.notification.max-attempts:5}")
  private int maxAttempts;

  /** The first gap; each further attempt doubles it. */
  @Value("${homeinv.notification.first-retry-seconds:60}")
  private long firstRetrySeconds;

  /**
   * What is waiting and due.
   *
   * @param now the present
   * @return at most one batch, oldest first
   */
  @Transactional(readOnly = true)
  public List<SecurityNotificationQueries.Due> dueNotifications(Instant now) {
    return queries.due(now, BATCH);
  }

  /**
   * Tries one notification once.
   *
   * @param notification what to deliver
   * @param now the present
   */
  @Transactional
  public void attempt(SecurityNotificationQueries.Due notification, Instant now) {
    int attemptNo = notification.attempts() + 1;

    Optional<NotificationChannel> channel =
        extensions
            .lookupForInstance(NotificationChannel.class)
            // The row names its channel and the instance grant names a plugin,
            // and the two have to agree. An operator who granted
            // `plugin-webhook` an instance-level capability would otherwise have
            // every password reset posted to a URL as an event document -- it
            // would fail, because a mail address is not an https target, but it
            // would fail after leaving the deployment rather than before.
            .filter(candidate -> servesChannel(candidate, notification.channelKey()));
    if (channel.isEmpty()) {
      // Not a refusal by the far side — there is no far side. Retried, because
      // an operator who grants the capability afterwards should find the queued
      // messages go out rather than a pile of dead letters from before.
      log.warn(
          "No plugin serves account notifications here: nothing is installed with an"
              + " instance-level grant (ADR-0066), so {} is waiting rather than delivered."
              + " An operator grants one under /api/v1/instance/plugins.",
          notification.id());
      queries.recordAttempt(
          notification.id(),
          attemptNo,
          "FAILED",
          "No plugin serves this channel for the instance");
      reschedule(notification, attemptNo, now);
      return;
    }

    try {
      NotificationChannel.Delivery delivery =
          channel
              .get()
              .deliver(
                  CallContext.forInstance("", notification.language(), 0),
                  new NotificationChannel.Message(
                      notification.address(),
                      notification.subject(),
                      notification.bodyText(),
                      notification.bodyHtml() == null ? "" : notification.bodyHtml(),
                      notification.language(),
                      Map.of(),
                      notification.idempotencyKey(),
                      List.of()));

      queries.recordAttempt(
          notification.id(),
          attemptNo,
          delivery.deduplicated() ? "DEDUPLICATED" : "ACCEPTED",
          delivery.detail());
      queries.markDelivered(notification.id(), attemptNo, delivery.providerMessageId());
    } catch (PluginException refused) {
      boolean worthRetrying = refused.retryable();
      queries.recordAttempt(
          notification.id(),
          attemptNo,
          worthRetrying ? "FAILED" : "REFUSED",
          refused.getMessage());
      if (worthRetrying) {
        reschedule(notification, attemptNo, now);
      } else {
        // "That is not an address" does not become one by being repeated.
        deadLetter(notification.id(), attemptNo);
      }
    }
  }

  /**
   * Whether this channel calls itself by the key the row asks for.
   *
   * <p>A plugin that cannot answer is treated as not serving the channel: it is the same state as
   * not being installed, and it is retried for the same reason.
   *
   * @param channel the instance-granted channel
   * @param channelKey what the row asks for
   * @return whether they agree
   */
  private boolean servesChannel(NotificationChannel channel, String channelKey) {
    try {
      return channelKey.equals(channel.describe(CallContext.forInstance("", "", 0)).channelKey());
    } catch (PluginException unavailable) {
      log.debug("The instance notification channel could not say what it is", unavailable);
      return false;
    }
  }

  private void reschedule(
      SecurityNotificationQueries.Due notification, int attemptNo, Instant now) {
    if (attemptNo >= maxAttempts) {
      deadLetter(notification.id(), attemptNo);
      return;
    }
    Duration wait = Duration.ofSeconds(firstRetrySeconds << (attemptNo - 1));
    queries.reschedule(notification.id(), attemptNo, now.plus(wait));
  }

  private void deadLetter(UUID notificationId, int attemptNo) {
    queries.deadLetter(notificationId, attemptNo);
    // Warning rather than info: a security notification that never arrived is
    // something an operator has to know about, because the person it was for is
    // the one who cannot tell.
    log.warn(
        "The account notification {} was not delivered after {} attempt(s) and is dead-lettered;"
            + " every attempt and what it said is in notification.security_delivery_attempt",
        notificationId,
        attemptNo);
  }
}
