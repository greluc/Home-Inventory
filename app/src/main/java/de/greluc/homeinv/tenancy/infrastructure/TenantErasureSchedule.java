/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tenancy.infrastructure;

import de.greluc.homeinv.tenancy.application.TenantErasureRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the erasures whose grace period has elapsed, once an hour, in the worker (REQ-TEN-011).
 *
 * <h2>Why the worker and not the API</h2>
 *
 * <p>Erasing a tenant reads and deletes across eight building blocks and may take minutes. A
 * request served by {@code api} has a thirty-second ceiling (REQ-SEC-065), and a background job in
 * a process that serves the internet competes with the requests it is meant not to slow down. 04 §4.1 gives the
 * worker exactly this: the same image, consuming and sweeping rather than serving.
 *
 * <p>It is also why this is {@code @Profile("worker")} rather than everywhere. Two processes running
 * the same sweep would each pick up the same tenant, and the second would find nothing to remove
 * and issue no second certificate — the insert is idempotent on the tenant — but they would still
 * spend an hour erasing in parallel for no reason.
 *
 * <h2>Hourly, not daily</h2>
 *
 * <p>13 §13.8's cadences are daily for the sweeps that reclaim space and hourly for the ones that
 * make something happen at a promised time. This is the second kind: a grace period that ended at
 * nine should not have to wait until midnight, because the person watching for it to happen is the
 * person who asked.
 */
@Component
@Profile("worker")
@Slf4j
@RequiredArgsConstructor
public class TenantErasureSchedule {

  private final TenantErasureRunner runner;

  /**
   * Erases what is due.
   *
   * <p>A fixed delay rather than a fixed rate: a run that took longer than an hour must not have a
   * second one starting on top of it, and {@code fixedDelay} measures from the end of the last.
   */
  @Scheduled(fixedDelayString = "${HOMEINV_TENANT_ERASURE_INTERVAL_MS:3600000}", initialDelay = 60_000)
  public void eraseDueTenants() {
    try {
      runner.eraseDueTenants();
    } catch (RuntimeException failed) {
      // The scheduler stops calling a task that throws. An erasure sweep that
      // stopped after one bad hour would leave every later request unhonoured,
      // and nothing would say so until somebody asked.
      log.error("The tenant erasure sweep failed; it will run again.", failed);
    }
  }
}
