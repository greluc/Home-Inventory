/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tenancy.api;

import lombok.Getter;

/**
 * The operation would take a tenant past one of its quotas (REQ-TEN-009).
 *
 * <p>Answered as {@code 403} with {@code type: …/quota-exceeded}, carrying the current and the
 * permitted amount — which is what 05 §5.1 requires of a quota refusal, and the reason both numbers
 * are on the exception rather than only in its message. A client that has to read English to show
 * "48 000 of 50 000 used" is one that shows the wrong number in the other language.
 *
 * <p>A {@code 403} and not a {@code 429}: waiting does not help, and a {@code Retry-After} would be
 * a lie. The one quota where waiting <em>does</em> help is {@code API_CALLS}, and even there what
 * helps is the next month rather than the next minute.
 */
@Getter
public class QuotaExceededException extends RuntimeException {

  /** Which bound was reached. */
  private final QuotaGuard.Quota quota;

  /** How much is in use, including the claim that was refused. */
  private final long current;

  /** How much is permitted. */
  private final long permitted;

  /**
   * @param quota which bound was reached
   * @param current how much would be in use
   * @param permitted how much is allowed
   */
  public QuotaExceededException(QuotaGuard.Quota quota, long current, long permitted) {
    super("The " + quota + " quota is " + permitted + " and this would need " + current + ".");
    this.quota = quota;
    this.current = current;
    this.permitted = permitted;
  }
}
