/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.notification.application;

import de.greluc.homeinv.notification.infrastructure.ReminderRuleQueries;
import de.greluc.homeinv.platform.TenantContext;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Finds the tenants with rules and runs each one's (REQ-NOTI-001).
 *
 * <h2>Why this is a separate bean</h2>
 *
 * <p>Because {@link ReminderRunner#runFor} is {@code @Transactional}, and Spring's transactions are
 * applied by a proxy: a call to {@code this.runFor(...)} from inside the same class goes straight
 * to the method and the annotation does <b>nothing</b>. The tenant loop therefore lives here and
 * calls {@code runner} as a dependency, which is exactly the arrangement {@code
 * DeliveryDispatcher} has with {@code DeliveryRunner} and for exactly this reason.
 *
 * <p>It was written as one class first, and the tests did not catch it — they call {@code runFor}
 * through the bean, so the proxy applied in the test and not in the scheduled run. An annotation
 * that works everywhere except production is worse than no annotation, because it also stops
 * anybody looking.
 *
 * <p>The transaction matters here rather than being tidiness: a reminder is <b>recorded</b> and
 * then <b>queued</b>, and a process that stopped between the two would have a rule that believes it
 * has already told somebody something it never sent.
 */
@Component
@RequiredArgsConstructor
public class ReminderDispatcher {

  private final ReminderRuleQueries queries;
  private final ReminderRunner runner;

  /**
   * Runs every tenant's rules.
   *
   * @param today the day to judge against
   * @return how many notifications were raised
   */
  public int runAll(LocalDate today) {
    int raised = 0;
    for (UUID tenantId : queries.tenantsWithRules()) {
      // The context around the transaction and never inside it: `SET LOCAL
      // app.tenant_id` is applied when the transaction begins, from the context
      // current at that moment.
      raised += TenantContext.callAs(tenantId, () -> runner.runFor(tenantId, today));
    }
    return raised;
  }
}
