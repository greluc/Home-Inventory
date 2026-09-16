/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.notification.application;

import de.greluc.homeinv.notification.infrastructure.SecurityNotificationQueries;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Delivers the account notifications that are due (REQ-NOTI-004, ADR-0066).
 *
 * <p>No tenant loop, because there is no tenant: the work is one queue for the whole instance. What
 * it shares with {@link DeliveryDispatcher} is the reason it is a separate bean from the runner —
 * each attempt is its own transaction, and a method calling another on the same object never goes
 * through the proxy that would start one.
 *
 * <p>No profile: this is the work, and {@link DeliverySchedule} decides when the worker does it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SecurityDeliveryDispatcher {

  private final SecurityDeliveryRunner runner;

  /**
   * Delivers everything due at a given moment.
   *
   * <p>Takes the time rather than reading the clock, so that a test can ask what happens an hour
   * from now without waiting an hour.
   *
   * @param now the moment to deliver for
   * @return how many notifications were attempted
   */
  public int deliverDue(Instant now) {
    int attempted = 0;
    for (SecurityNotificationQueries.Due notification : runner.dueNotifications(now)) {
      runner.attempt(notification, now);
      attempted++;
    }
    return attempted;
  }
}
