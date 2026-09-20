/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.notification.api;

/**
 * A rule names a trigger nothing can answer yet (REQ-NOTI-003).
 *
 * <p>{@link ReminderTrigger} declares the six REQ-NOTI-003 names; two of them have no {@link
 * ReminderSource} behind them, because a stocktake is stage 2 and no licence expiry date is stored.
 *
 * <p>Refused when the rule is <b>written</b>, by the person who can still pick another trigger.
 * The alternative is worse than an error: a rule accepted and then silently never firing is exactly
 * the failure a reminder feature cannot afford, and it would look like working software until the
 * day somebody needed it. The same argument REQ-SRCH-008 makes about refusing an unparseable filter
 * when it is saved rather than when it is run.
 */
public class UnservedTriggerException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param reason what is wrong, in a sentence meant for a person
   */
  public UnservedTriggerException(String reason) {
    super(reason);
  }
}
