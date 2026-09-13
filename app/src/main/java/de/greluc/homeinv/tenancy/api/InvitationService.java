/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tenancy.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Inviting somebody into a tenant, and accepting the invitation (REQ-TEN-004).
 *
 * <p>It is also the instance's registration path (REQ-AUTH-004): accepting for an address that has
 * no account creates that account. Without it a fresh instance has exactly one person on it, which
 * is what ADR-0053 leaves it with.
 */
public interface InvitationService {

  /**
   * An invitation, as the tenant that issued it sees it.
   *
   * <p>The token is not here. It exists once, in the answer to {@link #invite}, and afterwards only
   * as a hash (REQ-SEC-048) — so a list of invitations cannot be read to obtain one, and an
   * administrator who loses a link withdraws it and sends another.
   *
   * @param id the invitation
   * @param email the address it is bound to
   * @param role the role it grants on acceptance
   * @param expiresAt when it stops working
   * @param state {@code OPEN}, {@code ACCEPTED}, {@code REVOKED} or {@code EXPIRED}
   * @param invitedAt when it was issued
   */
  record InvitationView(
      UUID id, String email, String role, Instant expiresAt, String state, Instant invitedAt) {}

  /**
   * A freshly issued invitation, with the one copy of its token.
   *
   * @param invitation the invitation
   * @param token the secret the invited person presents. Returned exactly once. Where
   *     {@code plugin-smtp} is installed it also goes out by mail; where it is not, this is the only
   *     way the link reaches anybody, which 04 §4.3 describes as the expected reduced state
   */
  record IssuedInvitation(InvitationView invitation, String token) {}

  /**
   * One page of invitations.
   *
   * @param items the invitations
   * @param nextCursor where the next page starts, or null when this was the last
   */
  record InvitationPage(List<InvitationView> items, String nextCursor) {}

  /**
   * What accepting an invitation produced.
   *
   * @param tenantId the tenant joined
   * @param tenantName its display name, so the client can say where the person has landed
   * @param userId the account, whether it existed or was created by this acceptance
   * @param role the role granted
   * @param accountCreated whether this acceptance created the account, which decides whether the
   *     client sends the person to a sign-in or straight on
   */
  record AcceptedInvitation(
      UUID tenantId, String tenantName, UUID userId, String role, boolean accountCreated) {}

  /**
   * Issues an invitation for an address.
   *
   * <p>Single-use and time-limited by construction. Inviting an address that already has an open
   * invitation here replaces nothing and is refused as a conflict: two live invitations to one
   * address would be two tokens, and withdrawing the one somebody remembers would leave the other
   * working.
   *
   * @param email the address to invite
   * @param role the role to grant on acceptance
   * @param actor who is inviting
   * @return the invitation and its one token
   * @throws RoleEscalationException when the actor may not grant that role (REQ-TEN-010)
   * @throws InvitationAlreadyOpenException when this tenant has an unused invitation for the
   *     address already
   */
  IssuedInvitation invite(String email, String role, UUID actor);

  /**
   * One page of this tenant's invitations, newest first.
   *
   * @param cursor an opaque cursor from a previous page, or null for the first
   * @param limit how many at most, capped at 200
   * @return the page
   */
  InvitationPage invitations(String cursor, int limit);

  /**
   * Withdraws an invitation that has not been used.
   *
   * <p>Withdrawing one that was already used or withdrawn is not an error, for the same reason
   * deleting twice is not: a client retrying a request whose answer it never saw.
   *
   * @param invitationId the invitation
   * @param actor who is withdrawing it
   * @throws de.greluc.homeinv.platform.NotFoundException when this tenant has no such invitation
   */
  void revoke(UUID invitationId, UUID actor);

  /**
   * Redeems an invitation.
   *
   * <p>Runs without a tenant context and without a session, because neither exists yet: the token
   * is what identifies the tenant, through the {@code SECURITY DEFINER} lookup of 07 §7.5.
   *
   * <p>Three shapes, decided by what the invited address already is:
   *
   * <ul>
   *   <li>no account yet — this creates one from {@code displayName}, {@code locale} and
   *       {@code password}, which is the instance's registration path (REQ-AUTH-004);
   *   <li>an account, and {@code signedInAs} is that account — the membership is added;
   *   <li>an account, and {@code signedInAs} is null or somebody else — refused, because otherwise
   *       whoever held the token could attach a membership to a stranger's account.
   * </ul>
   *
   * @param token the secret from the invitation link
   * @param displayName what to call the new account, ignored when the account exists
   * @param locale the interface language for the new account, ignored when it exists
   * @param password the new account's password, ignored when it exists
   * @param signedInAs the account making the request, or null when nobody is signed in
   * @return what the acceptance produced
   * @throws InvitationUnusableException when the token is unknown, used, withdrawn or expired
   * @throws InvitationNotYoursException when the address has an account and the caller is not it
   */
  AcceptedInvitation accept(
      String token, String displayName, String locale, String password, UUID signedInAs);
}
