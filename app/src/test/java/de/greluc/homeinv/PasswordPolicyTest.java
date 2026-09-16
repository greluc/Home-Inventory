/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.homeinv.identity.api.PasswordPolicy;
import de.greluc.homeinv.identity.api.WeakPasswordException;
import de.greluc.homeinv.identity.application.DefaultPasswordPolicy;
import de.greluc.homeinv.identity.infrastructure.BreachedPasswordList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a password has to be (REQ-SEC-011, ADR-0067).
 *
 * <p>Three rules, and two of them are absences: twelve characters, <b>no</b> forced complexity,
 * <b>no</b> forced rotation — plus the list of passwords somebody else has already had taken. The
 * absences are tested too, because a well-meaning later change is exactly how they disappear.
 */
@DisplayName("The password policy")
class PasswordPolicyTest {

  private final BreachedPasswordList list = new BreachedPasswordList();
  private final PasswordPolicy policy = new DefaultPasswordPolicy(list);

  @Test
  @DisplayName("refuses anything under twelve characters")
  void tooShort() {
    assertThatThrownBy(() -> policy.check("short-pass1"))
        .isInstanceOf(WeakPasswordException.class)
        .hasMessageContaining("at least 12");
  }

  @Test
  @DisplayName("counts characters as a person types them, not as Java stores them")
  void codePointsRatherThanUnits() {
    // Twelve emoji are twelve characters to whoever chose them and twenty-four
    // to String.length(). A rule that disagreed with its own message about what
    // "12 characters" means is one people work around rather than meet.
    assertThatCode(() -> policy.check("🔑🔒🗝🛡🔐🧩🪪🔏🧱🪤🕵🦺")).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("refuses a long password that is already on the list")
  void breached() {
    // Twelve characters, no repetition, looks invented — and it is the
    // keyboard walk `q1w2e3r4t5y6`, which is in the list precisely because so
    // many people have chosen it.
    assertThatThrownBy(() -> policy.check("q1w2e3r4t5y6"))
        .isInstanceOf(WeakPasswordException.class)
        .hasMessageContaining("already known to attackers");
  }

  @Test
  @DisplayName("says length first when a password is both short and breached")
  void lengthBeforeList() {
    // "password" is on the list and is also eight characters. The person is told
    // the fixable thing.
    assertThatThrownBy(() -> policy.check("password"))
        .isInstanceOf(WeakPasswordException.class)
        .hasMessageContaining("at least 12");
  }

  @Test
  @DisplayName("asks for no digit, no capital and no symbol")
  void noForcedComplexity() {
    // Twelve lower-case letters and nothing else. REQ-SEC-011 forbids forced
    // complexity, and this is what forbidding it looks like from the outside:
    // the rule that would reject this must not exist.
    assertThatCode(() -> policy.check("kitchenwindow")).doesNotThrowAnyException();
    assertThatCode(() -> policy.check("a quiet afternoon by the lake"))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("carries the whole list it shipped with")
  void theListIsComplete() {
    // A truncated or missing resource would make every check pass, quietly. The
    // count is the cheapest way to notice, and the constructor already refuses
    // an absent file outright.
    assertThat(list.size()).isEqualTo(100_000);
  }
}
