/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.notification.application;

import de.greluc.homeinv.notification.api.SecurityNotifications;
import de.greluc.homeinv.notification.infrastructure.SecurityNotificationQueries;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Queues what the deployment owes an account (REQ-NOTI-004, ADR-0066).
 *
 * <p>Short, because there is nothing to decide. The tenant queue beside it resolves subscriptions
 * and can legitimately queue nothing; here the answer is always "the address on the account", and a
 * raise that produced no row would be a security message that was silently not sent.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultSecurityNotifications implements SecurityNotifications {

  private final SecurityNotificationQueries queries;

  @Override
  @Transactional
  public Queued raise(NewSecurityNotification message) {
    Queued queued = queries.queue(message);
    // The address is deliberately absent from the log line. What is useful when
    // somebody asks "was I told" is which account and which kind, and an address
    // in a log file is a piece of personal data in a place nobody is guarding
    // (REQ-PRIV-006).
    log.info(
        "Queued the account notification {} for {} ({})",
        message.kind(),
        message.userId(),
        queued.state());
    return queued;
  }

  @Override
  @Transactional(readOnly = true)
  public List<Queued> of(UUID userId, int limit) {
    return queries.of(userId, limit);
  }

  @Override
  @Transactional(readOnly = true)
  public List<Attempt> attempts(UUID notificationId) {
    return queries.attempts(notificationId);
  }
}
