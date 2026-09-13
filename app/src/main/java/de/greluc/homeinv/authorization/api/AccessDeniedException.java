/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.authorization.api;

/**
 * The caller's role does not hold the permission the operation needs.
 *
 * <p>Answered as {@code 403}, and only ever about a permission the caller genuinely lacks on a
 * resource they are otherwise entitled to know exists. The other case — a resource in another
 * tenant, or one this caller cannot see at all — is a {@code 404} and is never this exception:
 * telling somebody "you may not touch item X" confirms that item X exists, which is the fact they
 * were not supposed to learn (REQ-SEC-025).
 */
public class AccessDeniedException extends RuntimeException {

  /**
   * @param permission the permission that was required
   */
  public AccessDeniedException(Permission permission) {
    super("The caller's role does not hold " + permission.id() + ".");
  }

  /**
   * The same answer for an instance-level entitlement the account does not hold (ADR-0057).
   *
   * <p>One exception for both, because a client has one thing to do about either: ask somebody who
   * can grant it. Splitting them would put the difference between "your role is too low here" and
   * "your account is not entitled on this instance" into an error body, and the second half is a
   * fact about the instance's administration rather than about the request.
   *
   * @param entitlement the entitlement that was required
   */
  public AccessDeniedException(Entitlement entitlement) {
    super("The caller's account does not hold " + entitlement.id() + ".");
  }
}
