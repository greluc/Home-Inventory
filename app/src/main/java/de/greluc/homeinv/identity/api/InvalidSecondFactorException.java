/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.identity.api;

/**
 * The second factor presented is not valid (REQ-AUTH-002).
 *
 * <p>One exception for every way it can fail: a wrong code, a code from a time step already spent,
 * a recovery code already used, a code for an account that holds no second factor. Telling them
 * apart would say whether a guess was close, and "already used" would confirm that the code existed
 * — which is the same argument {@link InvalidCredentialsException} makes about an address.
 *
 * <p>Carries no message of its own, so nothing can accidentally be echoed back to the caller.
 */
public class InvalidSecondFactorException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public InvalidSecondFactorException() {
    super("Invalid second factor");
  }
}
