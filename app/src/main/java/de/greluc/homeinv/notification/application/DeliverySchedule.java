/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.notification.application;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * When the worker delivers (REQ-NOTI-005, 13 §13.8).
 *
 * <p>{@code @Profile("worker")} like every recurring task here: two processes delivering the same
 * notification is two people getting the same mail.
 *
 * <p>It holds the timer and nothing else. The work is {@link DeliveryDispatcher}'s, which carries no
 * profile so that a test can drive it without waiting for one.
 */
@Slf4j
@Component
@Profile("worker")
@RequiredArgsConstructor
public class DeliverySchedule {

  private final DeliveryDispatcher dispatcher;
  private final SecurityDeliveryDispatcher securityDispatcher;

  /**
   * Delivers everything due.
   *
   * <p>Every thirty seconds. A notification is queued the moment something raises it and its first
   * attempt is due immediately, so this interval is the worst case between an invitation being
   * created and going out.
   */
  @Scheduled(fixedDelayString = "${HOMEINV_NOTIFICATION_INTERVAL_MS:30000}", initialDelay = 15_000)
  public void deliver() {
    try {
      dispatcher.deliverDue(Instant.now());
    } catch (RuntimeException failed) {
      // Logged and swallowed: a scheduled task that throws stops being scheduled
      // in some runtimes, and notifications that silently stopped going out is
      // the failure this whole block is against.
      log.error("The notification delivery run failed; the next one will pick it up", failed);
    }

    // The account queue, in its own try. One queue failing must not stop the
    // other: a broken tenant channel would otherwise hold up the password reset
    // somebody is waiting for (REQ-NOTI-004, ADR-0066).
    try {
      securityDispatcher.deliverDue(Instant.now());
    } catch (RuntimeException failed) {
      log.error(
          "The account notification run failed; the next one will pick it up. Nobody is told"
              + " about their own account while this keeps happening",
          failed);
    }
  }
}
