/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.identity.application;

import de.greluc.homeinv.identity.api.PasswordPolicy;
import de.greluc.homeinv.identity.api.WeakPasswordException;
import de.greluc.homeinv.identity.infrastructure.BreachedPasswordList;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * The rules of {@code REQ-SEC-011}, applied wherever a password is chosen.
 *
 * <p>Counts <b>code points</b> rather than {@code String.length()}. A password of twelve emoji is
 * twelve characters to the person who typed it and twenty-four to {@code length()}, and a rule that
 * disagreed with its own error message about what "12 characters" means is a rule people work
 * around rather than meet.
 *
 * <p>Nothing here trims or normalises: a leading space is a character somebody chose, and silently
 * removing it would mean the password that was accepted is not the password that was stored.
 *
 * <h2>Length first, then the list</h2>
 *
 * <p>Order matters for what the person is told. Something short and breached is told it is short,
 * because that is the fixable thing; and the list is only reached by passwords long enough to have
 * passed, which today is about 1 500 of its 100 000 entries (ADR-0067).
 */
@Service
@RequiredArgsConstructor
public class DefaultPasswordPolicy implements PasswordPolicy {

  private final BreachedPasswordList breached;

  @Override
  public void check(String password) {
    if (password == null || password.codePointCount(0, password.length()) < MINIMUM_LENGTH) {
      throw new WeakPasswordException(
          "A password needs at least " + MINIMUM_LENGTH + " characters.");
    }
    if (breached.contains(password)) {
      // What the person is told and what they are not: that it is known, and
      // nothing about where from. "This password appears in a breach list" is
      // actionable; naming the list would only invite an argument about it.
      throw new WeakPasswordException(
          "This password appears in a list of passwords that are already known to attackers."
              + " Choose a different one.");
    }
  }
}
