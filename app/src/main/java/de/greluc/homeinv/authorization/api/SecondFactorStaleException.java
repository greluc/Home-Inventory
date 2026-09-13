/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.authorization.api;

/**
 * The operation needs the second factor proved again (REQ-AUTH-011).
 *
 * <p>Distinct from {@link SecondFactorMissingException}, which says the account has no
 * authenticator at all. Here there is one and it was proved too long ago — or in this session not
 * at all, which is the same thing to the person: they enter a code and try again.
 */
public class SecondFactorStaleException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public SecondFactorStaleException() {
    super(
        "This operation needs the second factor again. Post a code to"
            + " /api/v1/auth/mfa/step-up and repeat the request.");
  }
}
