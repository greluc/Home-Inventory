/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.notification.application;

import java.time.Clock;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the reminder rules (REQ-NOTI-001, REQ-NOTI-003).
 *
 * <p>Hourly rather than every thirty seconds like the delivery run, because the thing it watches
 * moves in days: a warranty expires on a date, and an hour of latency against a fortnight of
 * warning is not latency anybody can perceive. It also means the {@code SECURITY DEFINER} lookup
 * over every tenant runs twenty-four times a day rather than three thousand.
 *
 * <p>Raising is not sending. What this produces is queued notifications, which the delivery run
 * picks up on its own schedule — so a broker or a mail plugin being down delays a reminder rather
 * than losing it.
 */
@Slf4j
@Component
@Profile("worker")
@RequiredArgsConstructor
public class ReminderSchedule {

  private final ReminderDispatcher dispatcher;
  private final Clock clock;

  /** Raises whatever is due. */
  @Scheduled(fixedDelayString = "${HOMEINV_REMINDER_INTERVAL_MS:3600000}", initialDelay = 45_000)
  public void raiseDue() {
    try {
      int raised = dispatcher.runAll(LocalDate.now(clock));
      if (raised > 0) {
        log.info("The reminder run raised {} notification(s)", raised);
      }
    } catch (RuntimeException failed) {
      // Logged and swallowed, for `DeliverySchedule`'s reason: a scheduled task
      // that throws stops being scheduled in some runtimes, and reminders that
      // silently stopped is the failure this block exists against.
      log.error("The reminder run failed; the next one will pick it up", failed);
    }
  }
}
