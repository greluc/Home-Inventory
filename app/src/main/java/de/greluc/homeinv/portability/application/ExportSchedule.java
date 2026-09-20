/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.portability.application;

import de.greluc.homeinv.portability.infrastructure.ExportJobQueries;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Picks up queued exports (REQ-PORT-005).
 *
 * <p>Every thirty seconds, which is the same interval the notification delivery run uses and for
 * the same reason: it is the worst case between somebody pressing the button and anything
 * happening, and the person is waiting on the other side of it.
 *
 * <p>A separate bean from {@link ExportRunner} so the tenant loop and the per-tenant work are not
 * in one class — the arrangement {@code DeliveryDispatcher} has with {@code DeliveryRunner}, and
 * which keeps a proxied annotation from being silently bypassed by a {@code this.} call.
 */
@Slf4j
@Component
@Profile("worker")
@RequiredArgsConstructor
public class ExportSchedule {

  private final ExportJobQueries jobs;
  private final ExportRunner runner;

  /** Builds whatever has been asked for. */
  @Scheduled(fixedDelayString = "${HOMEINV_EXPORT_INTERVAL_MS:30000}", initialDelay = 20_000)
  public void buildQueued() {
    try {
      for (UUID tenantId : jobs.tenantsWithQueuedExports()) {
        runner.runAsTenant(tenantId);
      }
    } catch (RuntimeException failed) {
      // Logged and swallowed, for `DeliverySchedule`'s reason: a scheduled task
      // that throws stops being scheduled in some runtimes, and an export queue
      // that silently stopped is somebody waiting for an archive that never
      // arrives.
      log.error("The export run failed; the next one will pick it up", failed);
    }
  }
}
