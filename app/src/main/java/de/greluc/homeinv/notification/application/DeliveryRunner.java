/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.notification.application;

import de.greluc.homeinv.notification.infrastructure.NotificationQueries;
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
 * Tries one notification once, and records what happened either way (REQ-NOTI-005).
 *
 * <h2>Retries with a widening gap, then a dead letter</h2>
 *
 * <p>A mail server that is down is usually down for minutes, so the first retry is soon and each one
 * after it waits longer. After the last the notification is {@code DEAD_LETTERED} — it stops being
 * tried and stays readable, with every attempt and what each one said beside it. A message that
 * quietly stopped being retried is the failure this design is against.
 *
 * <h2>What counts as worth retrying</h2>
 *
 * <p>The plugin says. {@code UNAVAILABLE} and {@code DEADLINE_EXCEEDED} mean the far side is not
 * answering and the same call may work later; {@code INVALID_ARGUMENT} means that is not an address,
 * and trying a hundred times would not make it one. The second is dead-lettered on the first
 * attempt, which is the difference between a retry queue and a loop.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeliveryRunner {

  /** How many notifications one run takes, so a backlog does not become one long transaction. */
  static final int BATCH = 50;

  private final NotificationQueries queries;
  private final ExtensionRegistry extensions;

  /**
   * How many tries a notification gets before it is dead-lettered.
   *
   * <p>Five, with the backoff below, spans about half an hour — long enough for a restart and short
   * enough that somebody reading a delivery log sees a conclusion rather than a queue still turning.
   */
  @Value("${homeinv.notification.max-attempts:5}")
  private int maxAttempts;

  /** The first gap; each further attempt doubles it. */
  @Value("${homeinv.notification.first-retry-seconds:60}")
  private long firstRetrySeconds;

  /**
   * What is waiting and due for the current tenant.
   *
   * @param now the present
   * @return at most one batch, oldest first
   */
  @Transactional(readOnly = true)
  public List<NotificationQueries.Due> dueNotifications(Instant now) {
    return queries.due(now, BATCH);
  }

  /**
   * Tries one notification once.
   *
   * @param tenantId whose
   * @param notification what to deliver
   * @param now the present
   */
  @Transactional
  public void attempt(UUID tenantId, NotificationQueries.Due notification, Instant now) {
    int attemptNo = notification.attempts() + 1;

    Optional<NotificationChannel> channel = extensions.lookup(NotificationChannel.class, tenantId);
    if (channel.isEmpty()) {
      // No plugin serves the channel here. Not a refusal by the far side — there
      // is no far side — so it is retried: an operator installing `plugin-smtp`
      // should find the queued invitations go out, not a pile of dead letters
      // from before it existed (ADR-0028).
      queries.recordAttempt(
          tenantId, notification.id(), attemptNo, "FAILED", "No plugin serves this channel here");
      reschedule(tenantId, notification, attemptNo, now);
      return;
    }

    try {
      NotificationChannel.Delivery delivery =
          channel
              .get()
              .deliver(
                  new CallContext(tenantId, "", notification.language(), 0),
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
          tenantId,
          notification.id(),
          attemptNo,
          delivery.deduplicated() ? "DEDUPLICATED" : "ACCEPTED",
          delivery.detail());
      queries.markDelivered(tenantId, notification.id(), attemptNo, delivery.providerMessageId());
    } catch (PluginException refused) {
      boolean worthRetrying = refused.retryable();
      queries.recordAttempt(
          tenantId,
          notification.id(),
          attemptNo,
          worthRetrying ? "FAILED" : "REFUSED",
          refused.getMessage());
      if (worthRetrying) {
        reschedule(tenantId, notification, attemptNo, now);
      } else {
        // "That is not an address" does not become one by being repeated.
        deadLetter(tenantId, notification, attemptNo);
      }
    }
  }

  private void reschedule(
      UUID tenantId, NotificationQueries.Due notification, int attemptNo, Instant now) {
    if (attemptNo >= maxAttempts) {
      deadLetter(tenantId, notification, attemptNo);
      return;
    }
    // Doubling from the first gap: a mail server that is down is usually down for
    // minutes, and a fixed gap either gives up too early or hammers it.
    Duration wait = Duration.ofSeconds(firstRetrySeconds << (attemptNo - 1));
    queries.reschedule(tenantId, notification.id(), attemptNo, now.plus(wait));
  }

  private void deadLetter(UUID tenantId, NotificationQueries.Due notification, int attemptNo) {
    queries.deadLetter(tenantId, notification.id(), attemptNo);
    log.warn(
        "Notification {} was not delivered after {} attempt(s) and is dead-lettered;"
            + " every attempt and what it said is in notification.delivery_attempt",
        notification.id(),
        attemptNo);
  }
}
