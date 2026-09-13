/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.locations.api;

/**
 * The move would break the tree (REQ-CORE-045, REQ-CORE-047).
 *
 * <p>Two ways, and they are one exception because the caller does the same thing about both: choose
 * a different target. A place cannot move into its own subtree — that is the cycle the requirement
 * forbids, and the one thing a materialised path cannot survive — and it cannot move under a
 * category that has said what it takes and did not say this.
 *
 * <p>The depth ceiling is <em>not</em> here: it has {@link TooDeepException}, which already carries
 * the limit, and a caller that hit it has a different thing to fix.
 */
public class InvalidMoveException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * <p>The reason is the whole payload. It carries no location id, because every path that raises
   * this one names the location in the request — and a problem document that repeats the URL it
   * answers is a field a client has to be told to ignore.
   *
   * @param reason what is wrong, in a sentence meant for a person
   */
  public InvalidMoveException(String reason) {
    super(reason);
  }
}
