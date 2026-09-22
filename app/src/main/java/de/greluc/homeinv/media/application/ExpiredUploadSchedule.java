/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.media.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the expired upload sweep (REQ-MED-008).
 *
 * <p>Every fifteen minutes, which is an order of magnitude below the two-hour lifetime of an
 * unfinished upload: the thing being reclaimed is disk space and nothing goes wrong if it is
 * reclaimed a quarter of an hour late, while a cadence measured in hours would let a burst of
 * abandoned uploads sit for most of a day.
 */
@Slf4j
@Component
@Profile("worker")
@RequiredArgsConstructor
public class ExpiredUploadSchedule {

  private final ExpiredUploadSweep sweep;

  /** Removes what nobody came back for. */
  @Scheduled(fixedDelayString = "${HOMEINV_UPLOAD_SWEEP_INTERVAL_MS:900000}", initialDelay = 60_000)
  public void removeAbandoned() {
    try {
      sweep.sweep();
    } catch (RuntimeException failed) {
      // Logged and swallowed, for `OrphanedBlobSchedule`'s reason: a scheduled
      // task that throws stops being scheduled in some runtimes, and a sweep
      // that silently stopped is disk filling up with nobody told.
      log.error("The expired upload sweep failed; the next run will pick it up", failed);
    }
  }
}
