/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.platform;

/**
 * A value somebody else chose, made safe to put in a log line (REQ-NFR-041, REQ-SEC-068).
 *
 * <h2>What goes wrong without it</h2>
 *
 * <p>A log line is a record of what happened, and an attacker who can put a newline into one can
 * write a second line that never happened. "Operator X disabled plugin Y" becomes two entries, the
 * second of them saying whatever they liked — and the reader has no way to tell the forged line
 * from the real one, because by then it <i>is</i> a line.
 *
 * <p>This application writes ECS JSON to stdout, where a newline inside a string is escaped and the
 * attack does not land. That is a property of the <b>formatter</b>, though, and not of the call
 * site: the console pattern of {@code logging.pattern.level} is one line per event and an operator
 * reading with {@code journalctl} sees exactly what was written. A call site that is only safe
 * because of how it is rendered is a call site that stops being safe when somebody changes the
 * renderer.
 *
 * <h2>What it does, and what it deliberately does not</h2>
 *
 * <p>It removes three Unicode categories rather than a list of two characters:
 * <b>control</b>, so that a backspace cannot walk the cursor back over what was written and an
 * escape cannot start a control sequence; and <b>line separator</b> and <b>paragraph
 * separator</b>, because {@code U+2028} is neither ASCII nor a control character and starts a
 * new line in a great many readers. That last one is why this is written as categories: the
 * first version filtered {@link Character#isISOControl} and let {@code U+2028} straight
 * through, which its own test caught on the first run.
 *
 * <p>Everything else is left alone, because the point is a readable record of what arrived and
 * not a sanitised version of it.
 *
 * <p>It also bounds the length. A caller who cannot forge a line can still fill a disk with one.
 *
 * <p>It is <b>not</b> a substitute for refusing bad input where it arrives. Where a value has a
 * shape — a plugin id, a key, a code — the shape is checked at the boundary and the caller gets an
 * answer they can act on; this is what makes the log line safe whether or not somebody remembered
 * to, which is the only kind of safety worth having in a logging call.
 */
public final class LogSafe {

  /** The longest a borrowed value may be in one log line. */
  private static final int MAX_LENGTH = 200;

  /** What is shown in place of a value that was null. */
  private static final String ABSENT = "<none>";

  private LogSafe() {}

  /**
   * Whether a code point may stand in a log line.
   *
   * @param codePoint the character
   * @return false for anything that could start a line or drive a terminal
   */
  private static boolean cannotForgeALine(int codePoint) {
    int type = Character.getType(codePoint);
    return type != Character.CONTROL
        && type != Character.LINE_SEPARATOR
        && type != Character.PARAGRAPH_SEPARATOR;
  }

  /**
   * The value with anything that could forge a line taken out.
   *
   * @param value what somebody else chose, possibly {@code null}
   * @return a single-line, bounded rendering of it, never {@code null}
   */
  public static String value(String value) {
    if (value == null) {
      return ABSENT;
    }
    StringBuilder safe = new StringBuilder(Math.min(value.length(), MAX_LENGTH));
    value
        .codePoints()
        .limit(MAX_LENGTH)
        .filter(LogSafe::cannotForgeALine)
        .forEach(safe::appendCodePoint);
    if (value.length() > MAX_LENGTH) {
      safe.append("…");
    }
    return safe.toString();
  }
}
