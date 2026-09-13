/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.platform;

import java.util.UUID;

/**
 * The caller acted on a version of a resource that is no longer the current one (REQ-API-004).
 *
 * <p>The answer to it is {@code 412 Precondition Failed}: the request is well formed, the caller is
 * allowed, and what is wrong is that somebody else changed the thing in between. A client re-reads,
 * shows the difference and asks — which is the whole point of refusing rather than overwriting.
 *
 * <p>Distinct from the optimistic-lock failure the database raises. That one closes the window
 * between this check and the write; this one exists so that the usual case — a caller working from
 * a stale screen — is refused with an answer that names the two versions rather than with whatever
 * a constraint violation turns into.
 */
public class StaleVersionException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** The version the resource actually has now. */
  private final long current;

  /** The version the caller believed it had. */
  private final long expected;

  /**
   * Creates the exception.
   *
   * @param what the kind of resource, for the message — {@code item}, {@code location}
   * @param id which one
   * @param expected the version the caller sent
   * @param current the version the resource has
   */
  public StaleVersionException(String what, UUID id, long expected, long current) {
    super(
        "The "
            + what
            + " "
            + id
            + " has moved on: you acted on version "
            + expected
            + " and it is now at version "
            + current
            + ". Read it again before writing.");
    this.expected = expected;
    this.current = current;
  }

  /**
   * The version the resource has now, which is what a client should re-read against.
   *
   * @return the current version
   */
  public long getCurrent() {
    return current;
  }

  /**
   * The version the caller sent.
   *
   * @return the expected version
   */
  public long getExpected() {
    return expected;
  }
}
