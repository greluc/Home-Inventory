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
 * Runs the orphaned blob sweep (REQ-MED-011, 13 §13.8).
 *
 * <p>Weekly, which is what 13 §13.8 says and is the right order of magnitude: the thing being
 * reclaimed is disk space, and nothing goes wrong if it is reclaimed on Tuesday rather than Monday.
 * A shorter cadence would put a cross-tenant scan in the way of no measurable benefit.
 */
@Slf4j
@Component
@Profile("worker")
@RequiredArgsConstructor
public class OrphanedBlobSchedule {

  private final OrphanedBlobSweep sweep;

  /** Removes what nothing points at any more. */
  @Scheduled(fixedDelayString = "${HOMEINV_BLOB_SWEEP_INTERVAL_MS:604800000}", initialDelay = 90_000)
  public void removeOrphans() {
    try {
      sweep.sweep();
    } catch (RuntimeException failed) {
      log.error("The orphaned blob sweep failed; the next run will pick it up", failed);
    }
  }
}
