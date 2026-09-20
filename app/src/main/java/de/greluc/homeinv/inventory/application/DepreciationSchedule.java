/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.application;

import de.greluc.homeinv.inventory.infrastructure.DepreciationQueries;
import de.greluc.homeinv.platform.TenantContext;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the depreciated values current, once a day (REQ-LIFE-009).
 *
 * <h2>Why a run at all, when the calculation is a division</h2>
 *
 * <p>Because the valuation report sums a <b>column</b> (REQ-LIFE-008, REQ-LIFE-015), and a column
 * is what a database can sum. Computing the depreciation on every read would mean the report's SQL
 * joining to {@code catalog} for a useful life, which is a block reading another block's tables —
 * the thing ADR-0002 forbids, and forbids for a reason that is not stylistic.
 *
 * <p>Daily rather than hourly: a straight line over months does not move between breakfast and
 * lunch, and a value somebody quoted this morning should still be that value this afternoon.
 *
 * <p>In the {@code worker} profile only, like every other run of its kind. {@code api} answers
 * requests.
 */
@Slf4j
@Component
@Profile("worker")
@RequiredArgsConstructor
public class DepreciationSchedule {

  private final DepreciationQueries items;
  private final DepreciationRefresh refresh;
  private final Clock clock;

  /** Recomputes what this application depreciated, for every tenant that has any. */
  @Scheduled(fixedDelayString = "${HOMEINV_DEPRECIATION_INTERVAL_MS:86400000}", initialDelay = 60_000)
  public void refreshAll() {
    try {
      LocalDate today = LocalDate.now(clock);
      int written = 0;
      for (UUID tenantId : items.tenantsWithSomethingToDepreciate()) {
        written += TenantContext.callAs(tenantId, () -> refresh.refresh(today));
      }
      if (written > 0) {
        log.info("The depreciation run wrote {} current value(s)", written);
      }
    } catch (RuntimeException failed) {
      // Logged and swallowed, for the reason every other schedule gives: a task
      // that throws stops being scheduled in some runtimes, and a valuation that
      // silently stopped ageing is a report that is quietly wrong.
      log.error("The depreciation run failed; the next one will pick it up", failed);
    }
  }
}
