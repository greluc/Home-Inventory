/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tenancy.api;

/**
 * The revocation cannot be used: no such token, already used, or the grace period is over.
 *
 * <p><b>One answer for all three</b>, and a {@code 410} — the same shape an invitation gets, for the
 * same reason. Telling them apart would let whoever holds a link learn that a tenant here was asked
 * to be erased, and "this no longer works" is true of every one of the three.
 */
public class RevocationUnusableException extends RuntimeException {

  /** Creates the refusal. */
  public RevocationUnusableException() {
    super("This link no longer works. Ask the owner to sign in and check the tenant's state.");
  }
}
