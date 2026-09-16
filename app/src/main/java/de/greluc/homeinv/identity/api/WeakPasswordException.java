/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.identity.api;

/**
 * A password the policy will not accept (REQ-SEC-011).
 *
 * <p>Carries a message meant for the person choosing the password rather than a code for a client
 * to branch on: there is one rule to fail, and telling somebody "at least 12 characters" is more
 * use than a token they have to look up.
 */
public class WeakPasswordException extends RuntimeException {

  @java.io.Serial private static final long serialVersionUID = 1L;

  /**
   * Says what is wrong with it.
   *
   * @param message what the person choosing the password should be told
   */
  public WeakPasswordException(String message) {
    super(message);
  }
}
