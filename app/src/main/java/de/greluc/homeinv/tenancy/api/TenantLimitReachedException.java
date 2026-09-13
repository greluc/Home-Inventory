/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tenancy.api;

import lombok.Getter;

/**
 * The account already owns as many tenants as it may (REQ-TEN-002, REQ-TEN-009).
 *
 * <p>Answered as {@code 403} with {@code type: …/quota-exceeded}, carrying the current and the
 * permitted amount — which is what 05 §5.1 says a quota refusal looks like, and the reason both
 * numbers are on the exception rather than only in the message. A client that has to parse prose to
 * show "3 of 3 used" is a client that will show the wrong number in one language.
 */
@Getter
public class TenantLimitReachedException extends RuntimeException {

  /** How many the account owns now. */
  private final long current;

  /** How many it may own. */
  private final int permitted;

  /**
   * @param current how many tenants the account already owns
   * @param permitted how many it may own in total
   */
  public TenantLimitReachedException(long current, int permitted) {
    super("The account owns " + current + " of the " + permitted + " tenants it may own.");
    this.current = current;
    this.permitted = permitted;
  }
}
