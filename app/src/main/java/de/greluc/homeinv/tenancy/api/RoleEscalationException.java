/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tenancy.api;

import lombok.Getter;

/**
 * Somebody tried to grant more than they hold (REQ-TEN-010).
 *
 * <p>Answered as {@code 403}. The attempt is logged as well as refused, which the requirement asks
 * for in so many words: an administrator repeatedly trying to make somebody an owner is either a
 * misunderstanding worth correcting or an escalation worth seeing, and from one line the two look
 * the same.
 *
 * <p>It covers the removal direction too. Taking away a role one could not grant is the same
 * escalation read backwards: an administrator who could remove the owner could remove every owner
 * and then be the only person left who may invite.
 */
@Getter
public class RoleEscalationException extends RuntimeException {

  /** The role the actor holds. */
  private final String actorRole;

  /** The role they tried to grant, or that the person they tried to remove holds. */
  private final String targetRole;

  /**
   * @param actorRole what the actor holds
   * @param targetRole what they reached for
   */
  public RoleEscalationException(String actorRole, String targetRole) {
    super("A " + actorRole + " cannot grant or withdraw " + targetRole + ".");
    this.actorRole = actorRole;
    this.targetRole = targetRole;
  }
}
