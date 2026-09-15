/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.audit.application;

import de.greluc.homeinv.audit.infrastructure.ChainAnchors;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the hourly anchor (REQ-SEC-096, 13 §13.8).
 *
 * <p>{@code @Profile("worker")} for the reason every scheduled task here carries it: two processes
 * running the same job is two processes racing, and {@code api} exists to answer requests. A
 * deployment without a worker anchors nothing, which is a gap the verification run reports rather
 * than a silence.
 *
 * <p>Every fifteen minutes rather than hourly. The job anchors <b>whole past hours</b> and the
 * current one is never anchored, so running it four times an hour costs three findings of nothing
 * and means a restart near the hour boundary does not skip a window.
 */
@Slf4j
@Component
@Profile("worker")
@RequiredArgsConstructor
public class AnchorSchedule {

  private final ChainAnchors anchors;

  /**
   * Anchors whatever windows have closed since the last run.
   *
   * <p>{@code fixedDelay} and not {@code fixedRate}: measured from the end of the previous run, so
   * a run that takes longer than the interval is not immediately followed by another on top of it.
   */
  @Scheduled(fixedDelayString = "${HOMEINV_AUDIT_ANCHOR_INTERVAL_MS:900000}", initialDelay = 30_000)
  public void anchor() {
    try {
      anchors.anchorDueWindows(Instant.now());
    } catch (RuntimeException failed) {
      // Logged and swallowed: a scheduled task that throws stops being scheduled
      // in some runtimes, and an instance that stopped anchoring silently is the
      // failure this whole mechanism exists to make visible.
      log.error("The audit anchor run failed; the next window will catch up", failed);
    }
  }
}
