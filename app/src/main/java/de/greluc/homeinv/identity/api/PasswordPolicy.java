/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.identity.api;

/**
 * What a password has to be, and deliberately what it does not (REQ-SEC-011).
 *
 * <h2>Length, and nothing else about its shape</h2>
 *
 * <p>Twelve characters minimum. <b>No</b> forced complexity — no "one upper case, one digit, one
 * symbol" — and <b>no</b> forced rotation. Both are in the requirement as absences rather than
 * omissions: complexity rules push people towards {@code Passw0rd!} and rotation pushes them
 * towards {@code Passw0rd!2}, and neither makes an account harder to break into.
 *
 * <p>The rule is the same wherever a password is chosen — registration, a change, a reset — because
 * a policy that applies on one path and not another is the policy of the path that does not have
 * it.
 */
public interface PasswordPolicy {

  /**
   * The shortest password the policy accepts.
   *
   * <p>Published so that a client can say so before the round trip rather than only after it.
   */
  int MINIMUM_LENGTH = 12;

  /**
   * Checks a password, and says what is wrong when something is.
   *
   * @param password the password as the person typed it, never hashed and never logged
   * @throws WeakPasswordException when the policy refuses it
   */
  void check(String password);
}
