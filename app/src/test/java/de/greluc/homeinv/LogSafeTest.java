/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.homeinv.platform.LogSafe;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A value somebody else chose cannot write a log line of its own (REQ-NFR-041, REQ-SEC-068).
 *
 * <h2>Why this is worth testing rather than trusting</h2>
 *
 * <p>The attack is one line long: put a newline in a plugin id and the record of what happened
 * gains an entry that never happened, indistinguishable from the real ones because by then it
 * <i>is</i> one. This application writes ECS JSON, where the newline is escaped — but that is a
 * property of the formatter, and the console pattern an operator reads with {@code journalctl} is
 * one line per event.
 *
 * <p>Found by CodeQL on 2026-09-22, on a log line added the day before.
 *
 * <p>No container, no Spring.
 */
@DisplayName("A borrowed value in a log line")
class LogSafeTest {

  @ParameterizedTest(name = "{0}")
  @DisplayName("cannot start a second line")
  @ValueSource(
      strings = {
        "plugin\nOperator root disabled everything",
        "plugin\rOperator root disabled everything",
        "plugin\r\nOperator root disabled everything",
        "pluginOperator root disabled everything",
        "plugin Operator root disabled everything"
      })
  void noNewlineSurvives(String forged) {
    assertThat(LogSafe.value(forged)).doesNotContain("\n", "\r", "", " ");
  }

  @Test
  @DisplayName("cannot rewrite what is already on the screen")
  void noControlSequenceSurvives() {
    // A backspace walks the cursor back over what was written and an escape
    // starts a control sequence: both have been used to make a terminal show
    // something other than what the file holds, which is the same attack
    // without a newline in it.
    assertThat(LogSafe.value("real\b\b\b\bfake")).isEqualTo("realfake");
    assertThat(LogSafe.value("plain\u001b[2Ktext")).isEqualTo("plain[2Ktext");
  }

  @Test
  @DisplayName("is left alone when there is nothing wrong with it")
  void ordinaryValuesPassThrough() {
    // The point is a readable record of what arrived, not a sanitised version
    // of it. A guard that mangled ordinary values would be one somebody removes.
    assertThat(LogSafe.value("de.greluc.homeinv.plugin.smtp"))
        .isEqualTo("de.greluc.homeinv.plugin.smtp");
    assertThat(LogSafe.value("^[A-Z]{3}$ — Größe")).isEqualTo("^[A-Z]{3}$ — Größe");
  }

  @Test
  @DisplayName("cannot fill a disk either")
  void theLengthIsBounded() {
    String enormous = "a".repeat(10_000);

    String written = LogSafe.value(enormous);

    // A caller who cannot forge a line can still write ten thousand characters
    // of one, once per request.
    assertThat(written).hasSize(201).endsWith("…");
  }

  @Test
  @DisplayName("says so rather than printing the word null")
  void absenceIsNamed() {
    assertThat(LogSafe.value(null)).isEqualTo("<none>");
  }
}
