/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.identity.api;

/**
 * The account already has a confirmed second factor (REQ-AUTH-002).
 *
 * <p>Beginning a new enrolment over a confirmed one would replace the working authenticator with an
 * unproven one, and a person who then lost the new QR code would be locked out with a valid app on
 * their phone. Removing the old one is a separate call, and it asks for a code.
 */
public class SecondFactorAlreadyEnrolledException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public SecondFactorAlreadyEnrolledException() {
    super("A second factor is already enrolled");
  }
}
