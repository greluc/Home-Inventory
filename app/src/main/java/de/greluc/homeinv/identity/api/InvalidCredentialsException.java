/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.identity.api;

/**
 * The credentials are not valid.
 *
 * <p>One exception for every failure a caller is allowed to learn about: unknown address, wrong
 * password, locked account, a user with no tenant. Carrying which one would answer the question
 * "does this address have an account here", and the reason is logged instead so an operator can
 * still tell them apart (REQ-SEC-110).
 *
 * <p>Carries no message of its own, so nothing can accidentally be echoed back to the caller.
 *
 * <p>In the published {@code api} package, not beside the code that raises it. An exception a
 * caller is expected to catch is part of the contract, and one that is not published cannot be
 * caught without reaching into another block's internals - which the modularity test reports, and
 * which is how this class came to be here.
 */
public class InvalidCredentialsException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public InvalidCredentialsException() {
    super("Invalid credentials");
  }
}
