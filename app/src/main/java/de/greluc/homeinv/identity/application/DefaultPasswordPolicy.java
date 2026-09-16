/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.identity.application;

import de.greluc.homeinv.identity.api.PasswordPolicy;
import de.greluc.homeinv.identity.api.WeakPasswordException;
import org.springframework.stereotype.Service;

/**
 * The length rule of {@code REQ-SEC-011}, applied wherever a password is chosen.
 *
 * <p>Counts <b>code points</b> rather than {@code String.length()}. A password of twelve emoji is
 * twelve characters to the person who typed it and twenty-four to {@code length()}, and a rule that
 * disagreed with its own error message about what "12 characters" means is a rule people work
 * around rather than meet.
 *
 * <p>Nothing here trims or normalises: a leading space is a character somebody chose, and silently
 * removing it would mean the password that was accepted is not the password that was stored.
 */
@Service
public class DefaultPasswordPolicy implements PasswordPolicy {

  @Override
  public void check(String password) {
    if (password == null || password.codePointCount(0, password.length()) < MINIMUM_LENGTH) {
      throw new WeakPasswordException(
          "A password needs at least " + MINIMUM_LENGTH + " characters.");
    }
  }
}
