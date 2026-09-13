/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tenancy.api;

import lombok.Getter;

/**
 * This tenant already has an unused invitation for that address.
 *
 * <p>Answered as {@code 409}. Not a convenience: two live invitations to one address are two
 * tokens, and withdrawing the one somebody remembers would leave the other one working. The
 * administrator withdraws the open one and issues a new invitation, which is one act they can see
 * the result of rather than two they cannot.
 */
@Getter
public class InvitationAlreadyOpenException extends RuntimeException {

  /** The invitation that is already open, so a client can offer to withdraw it. */
  private final java.util.UUID invitationId;

  /**
   * @param invitationId the open invitation for this address
   */
  public InvitationAlreadyOpenException(java.util.UUID invitationId) {
    super("This address already has an open invitation to this tenant.");
    this.invitationId = invitationId;
  }
}
