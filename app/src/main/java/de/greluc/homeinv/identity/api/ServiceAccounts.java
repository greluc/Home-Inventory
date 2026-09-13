/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.identity.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Machine access to a tenant: tokens with a role and an expiry (REQ-AUTH-010, 12 §12.4).
 *
 * <p>A service account is not a person and has no password, no second factor and no session. It
 * presents a token on every request — {@code Authorization: Bearer} — and what it may do is decided
 * by the same {@code AccessControl} that answers for a member, because it holds the same pair: one
 * of the six built-in roles, and optionally a tenant-owned definition extending it.
 *
 * <p>Two properties the requirement states outright, and both are structural here rather than
 * advisory. <b>The token is shown exactly once</b>: what is stored is its SHA-256, so nothing can
 * show it again. <b>It expires</b>: the column is not nullable, and a token past its date is
 * refused like one that was never issued.
 *
 * <p>What a service account cannot do is anything that asks for the second factor again
 * (REQ-AUTH-011) — granting a role, inviting somebody, defining a role, opening a sensitive field,
 * asking for the tenant to be erased. It has no factor to prove, so those requests are refused.
 * That is deliberate: the operations a machine may not perform unattended are exactly the ones a
 * person is asked to confirm.
 */
public interface ServiceAccounts {

  /**
   * A service account as its tenant sees it.
   *
   * @param id the account
   * @param name what it is called
   * @param description what it is for, or null
   * @param role the built-in role it holds
   * @param roleDefinitionId the tenant-owned role extending it, or null
   * @param expiresAt when it stops working
   * @param lastUsedAt when it last authenticated, or null when it never has
   */
  record ServiceAccountView(
      UUID id,
      String name,
      String description,
      String role,
      UUID roleDefinitionId,
      Instant expiresAt,
      Instant lastUsedAt) {}

  /**
   * A service account that has just been created, and its token.
   *
   * @param account the account
   * @param token the token, in clear, for the only time it is readable
   */
  record IssuedServiceAccount(ServiceAccountView account, String token) {

    /**
     * The account, and never the token.
     *
     * @return the record with the token masked
     */
    @Override
    public String toString() {
      return "IssuedServiceAccount[account=" + account + ", token=***]";
    }
  }

  /**
   * Every live service account of the tenant being acted for, newest first.
   *
   * @param limit how many at most
   * @return the accounts
   */
  List<ServiceAccountView> all(int limit);

  /**
   * Issues one.
   *
   * @param name what to call it
   * @param description what it is for, or null
   * @param role the built-in role it holds
   * @param roleDefinitionId a tenant-owned role extending it, or null
   * @param expiresAt when it stops working
   * @param actor who is creating it
   * @return the account and its token, which the caller shows once and does not store
   * @throws de.greluc.homeinv.platform.NotFoundException when the tenant has no such role
   *     definition
   * @throws IllegalArgumentException when the expiry is in the past, which would be a token that
   *     never worked
   */
  IssuedServiceAccount issue(
      String name,
      String description,
      String role,
      UUID roleDefinitionId,
      Instant expiresAt,
      UUID actor);

  /**
   * Revokes one, at once.
   *
   * @param id the account
   * @param actor who is revoking it
   * @throws de.greluc.homeinv.platform.NotFoundException when this tenant has no such account
   */
  void revoke(UUID id, UUID actor);

  /**
   * Who a token belongs to, if it is still good.
   *
   * <p>The one call that runs with no tenant context: the token is what decides which tenant the
   * caller is acting for, so it goes through the {@code SECURITY DEFINER} lookup of 07 §7.5.
   *
   * @param token the token as presented
   * @return who is calling, or empty when the token is unknown, revoked or expired — one answer for
   *     all three, because telling them apart says which tokens once existed
   */
  java.util.Optional<AuthenticatedUser> authenticate(String token);
}
