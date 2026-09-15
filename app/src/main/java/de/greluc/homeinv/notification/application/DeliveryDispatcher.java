/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.notification.application;

import de.greluc.homeinv.notification.infrastructure.NotificationQueries;
import de.greluc.homeinv.platform.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Delivers what is due, tenant by tenant (REQ-NOTI-005).
 *
 * <h2>Why the loop is here and not in {@link DeliveryRunner}</h2>
 *
 * <p>Each attempt is its own transaction, so one notification failing does not roll back the
 * attempts recorded for the others. A method calling another on the same object goes straight to it
 * and never through the proxy that starts a transaction — so the loop has to live in a different
 * bean from the work, or every attempt would quietly share one.
 *
 * <p>No profile: this is the work, and {@link DeliverySchedule} is what decides when the worker does
 * it. A test drives this one directly rather than waiting for a timer.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeliveryDispatcher {

  private final NotificationQueries queries;
  private final DeliveryRunner runner;

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
    for (UUID tenantId : queries.tenantsWithSomethingDue(now)) {
      // The context around the transaction and never inside it: `SET LOCAL
      // app.tenant_id` is applied when the transaction begins, from the context
      // current at that moment.
      List<NotificationQueries.Due> due =
          TenantContext.callAs(tenantId, () -> runner.dueNotifications(now));
      for (NotificationQueries.Due notification : due) {
        TenantContext.runAs(tenantId, () -> runner.attempt(tenantId, notification, now));
        attempted++;
      }
    }
    return attempted;
  }
}
