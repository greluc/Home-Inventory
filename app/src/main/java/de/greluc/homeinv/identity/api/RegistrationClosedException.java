/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.identity.api;

/**
 * This instance creates no accounts (REQ-AUTH-004).
 *
 * <p>Raised when an invitation for an address nobody has would have to create one, and
 * {@code HOMEINV_REGISTRATION_MODE} is {@code closed}. The invitation itself is not the problem and
 * is not consumed: somebody who already has an account can accept the same one.
 */
public class RegistrationClosedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public RegistrationClosedException() {
    super(
        "This instance does not create accounts. Ask the operator for one, then accept the"
            + " invitation while signed in.");
  }
}
