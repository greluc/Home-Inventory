/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.portability.application;

import de.greluc.homeinv.portability.infrastructure.ImportJobQueries;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reads whatever has been uploaded, in the worker (REQ-PORT-003, REQ-PORT-005).
 *
 * <p>In the {@code worker} profile only, like every other run of its kind: {@code api} answers
 * requests and this takes minutes. The two share a database and nothing else.
 */
@Slf4j
@Component
@Profile("worker")
@RequiredArgsConstructor
public class ImportSchedule {

  private final ImportJobQueries jobs;
  private final ImportRunner runner;

  /** Reads whatever has been uploaded. */
  @Scheduled(fixedDelayString = "${HOMEINV_IMPORT_INTERVAL_MS:30000}", initialDelay = 25_000)
  public void readUploaded() {
    try {
      for (UUID tenantId : jobs.tenantsWithQueuedImports()) {
        runner.runAsTenant(tenantId);
      }
    } catch (RuntimeException failed) {
      // Logged and swallowed, for `ExportSchedule`'s reason: a scheduled task
      // that throws stops being scheduled in some runtimes, and an import queue
      // that silently stopped is somebody watching a job that never moves.
      log.error("The import run failed; the next one will pick it up", failed);
    }
  }
}
