/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.notification.application;

import de.greluc.homeinv.notification.api.Notifications;
import de.greluc.homeinv.notification.infrastructure.NotificationQueries;
import de.greluc.homeinv.platform.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Queues notifications and keeps what people asked for.
 *
 * <p>Delivery is not here. {@link DeliveryRunner} does that, in the worker, because a request that
 * waited for a mail server would be a request that fails when the mail server does.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultNotifications implements Notifications {

  private final NotificationQueries queries;

  @Override
  @Transactional
  public List<QueuedNotification> raise(NewNotification notification) {
    UUID tenantId = TenantContext.require();

    List<Subscription> wanted =
        queries.subscriptions(notification.userId()).stream()
            .filter(Subscription::enabled)
            .filter(subscription -> subscription.kind().equals(notification.kind()))
            .toList();

    if (wanted.isEmpty()) {
      // Not a failure. Somebody who asked for nothing gets nothing, and the
      // caller finds out by getting an empty list rather than by an exception it
      // would have to ignore.
      log.debug(
          "Nobody subscribed to {} for user {}; nothing queued",
          notification.kind(),
          notification.userId());
      return List.of();
    }

    List<QueuedNotification> queued = new ArrayList<>(wanted.size());
    for (Subscription subscription : wanted) {
      // One idempotency key per channel: the same news on two channels is two
      // messages, and a shared key would make the second one look like a repeat
      // of the first.
      queued.add(
          queries.queue(
              tenantId,
              notification.userId(),
              notification.kind(),
              subscription.channelKey(),
              subscription.address(),
              notification.subject() == null ? "" : notification.subject(),
              notification.bodyText(),
              notification.bodyHtml(),
              notification.language() == null ? "en" : notification.language(),
              notification.idempotencyKey() + ":" + subscription.channelKey()));
    }
    return List.copyOf(queued);
  }

  @Override
  @Transactional(readOnly = true)
  public List<Subscription> subscriptions(UUID userId) {
    return queries.subscriptions(userId);
  }

  @Override
  @Transactional
  public Subscription subscribe(
      UUID userId, String kind, String channelKey, String address, boolean enabled) {
    return queries.upsertSubscription(
        TenantContext.require(), userId, kind, channelKey, address, enabled);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<QueuedNotification> notification(UUID notificationId) {
    return queries.notification(notificationId);
  }

  @Override
  @Transactional(readOnly = true)
  public List<DeliveryAttempt> attempts(UUID notificationId) {
    return queries.attempts(notificationId);
  }
}
