/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.authorization.api;

/**
 * The role requires a second factor and the account has none (REQ-AUTH-003).
 *
 * <p>Raised in two places and it means the same thing in both: a session whose role is
 * {@code OWNER}, {@code ADMIN} or one that reads sensitive fields, held by an account with no
 * authenticator; and a grant of sensitive field visibility to a role somebody in that position
 * holds.
 *
 * <p>It is not a refusal of the role. The membership stands and the person keeps it — what they
 * cannot do is act on it until they enrol, which is a sentence a client can act on rather than a
 * dead end (REQ-AUTH-003, 12 §12.4).
 */
public class SecondFactorMissingException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param detail what the caller should be told to do about it
   */
  public SecondFactorMissingException(String detail) {
    super(detail);
  }
}
