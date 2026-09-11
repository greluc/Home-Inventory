/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.platform;

/**
 * The pagination cursor is malformed, forged, or belongs to a different query.
 *
 * <p>One exception for all three (REQ-SEC-106, REQ-SRCH-009). A caller can do nothing different
 * about them, and telling them apart would tell whoever is forging one which part was wrong.
 *
 * <p>The alternative to raising is worse than it looks: an unverified cursor does not fail, it
 * silently resumes at a different position, and the client pages past rows it should have seen
 * without ever learning that it did.
 */
public class InvalidCursorException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public InvalidCursorException() {
    super("The pagination cursor is not valid for this query");
  }
}
