/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tenancy.api;

/**
 * The invited address already has an account, and the caller is not signed in as it.
 *
 * <p>Answered as {@code 403}. Whoever holds the token was sent it at that address, which is good
 * enough to <em>create</em> the account it names — nobody else exists to be harmed. It is not good
 * enough to attach a membership to an account that already exists and belongs to somebody: that
 * person would find themselves in a tenant they never joined, and an invitation forwarded or
 * intercepted would be the way to put them there.
 *
 * <p>The way through is to sign in as the invited address and open the link again.
 */
public class InvitationNotYoursException extends RuntimeException {

  /** Creates the refusal. */
  public InvitationNotYoursException() {
    super("Sign in with the invited address, then open the invitation again.");
  }
}
