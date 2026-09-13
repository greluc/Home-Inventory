/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tenancy.api;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What this block needs to know about the people it administers.
 *
 * <p>A port declared here and implemented by {@code identity}, which owns the accounts. The
 * direction is what keeps the two blocks acyclic: {@code identity} already depends on this block
 * for the login's tenant lookup, so a call the other way would close a cycle. The same arrangement
 * {@code authorization.AccountEntitlements} has, and the same reason.
 *
 * <p>It is deliberately narrow. A member list needs a name and an address to show; an invitation
 * needs to know whether an address already belongs to somebody, and to create an account when it
 * does not. Nothing here reads a credential, a session or another tenant's membership.
 */
public interface AccountRegistry {

  /**
   * A person, as a member list shows them.
   *
   * @param id the account
   * @param email the address they sign in with
   * @param displayName what the interface calls them
   */
  record Account(UUID id, String email, String displayName) {}

  /**
   * One account by id.
   *
   * @param userId the account
   * @return the account, or empty when there is no live one
   */
  Optional<Account> byId(UUID userId);

  /**
   * Several accounts at once, for a member list.
   *
   * <p>A batch and not a loop, because a member list is a page of rows and a lookup per row is the
   * query pattern that makes a page of twenty cost twenty round trips.
   *
   * @param userIds the accounts
   * @return the ones that exist, by id; an id with no live account is simply absent
   */
  Map<UUID, Account> byIds(Collection<UUID> userIds);

  /**
   * One account by login address.
   *
   * @param email the address, matched case-insensitively
   * @return the account, or empty when no live account has that address
   */
  Optional<Account> byEmail(String email);

  /**
   * Creates an account for somebody accepting an invitation (REQ-AUTH-004).
   *
   * <p>This is the instance's registration path. There is no open sign-up: an invitation is what
   * makes an account, which is what {@code HOMEINV_REGISTRATION_MODE=invite_only} means in
   * practice. The caller has already established that the address belongs to a usable invitation.
   *
   * @param email the invited address, which becomes the credential
   * @param displayName what the interface calls them
   * @param locale the interface language to start in
   * @param password the plaintext, hashed by the implementation and held no longer than this call
   * @return the new account's id
   * @throws IllegalStateException when the address is already taken, which the caller should have
   *     ruled out and which must not silently produce a second account
   */
  UUID register(String email, String displayName, String locale, String password);
}
