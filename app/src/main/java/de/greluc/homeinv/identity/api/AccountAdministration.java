/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.identity.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What the instance operator may do to an account (ADR-0057).
 *
 * <p>Published for {@code /api/v1/instance/**} and for nothing else. Every method here reads or
 * writes an account across every tenant boundary there is, which is precisely why the endpoints
 * that call them are the only ones gated on
 * {@link de.greluc.homeinv.authorization.api.Entitlement#INSTANCE_OPERATOR} — the check is on the
 * endpoint rather than in here, because this block answers "who is this person" and never "may
 * they" (04 §4.3, ADR-0005).
 *
 * <p>It administers <b>entitlements</b> and nothing else. It sets no password, creates no account
 * and deletes none: the first account comes from the one-shot {@code bootstrap} service (ADR-0053)
 * and every later one from an invitation, so an operator who could mint accounts here would be a
 * third way in that neither of those two records.
 */
public interface AccountAdministration {

  /**
   * An account, as the operator's view of the instance shows it.
   *
   * <p>Carries no password hash, no {@code passwordChangedAt} and no session information. An
   * operator administers entitlements; the credential is the account holder's.
   *
   * @param id the account
   * @param email the login address
   * @param displayName what the interface calls them
   * @param instanceOperator whether they administer the instance
   * @param mayCreateTenants whether they may create tenants
   * @param tenantLimit how many they may own, or null when the instance-wide default applies
   * @param locked whether the account is barred from authenticating
   */
  record AccountView(
      UUID id,
      String email,
      String displayName,
      boolean instanceOperator,
      boolean mayCreateTenants,
      Integer tenantLimit,
      boolean locked) {}

  /**
   * Finds an account by its login address.
   *
   * <p>The operator's way in, because an operator knows the address somebody wrote to them and not
   * the id. It is an enumeration surface by construction — that is what looking somebody up is —
   * and it is why it sits behind the one entitlement that is granted by hand.
   *
   * @param email the address, matched case-insensitively
   * @return the account, or empty when no live account has that address
   */
  Optional<AccountView> byEmail(String email);

  /**
   * One account by id.
   *
   * @param userId the account
   * @return the account, or empty
   */
  Optional<AccountView> byId(UUID userId);

  /**
   * One page of the accounts that administer the instance.
   *
   * <p>The answer to "who can do this to us", which an operator and an auditor both ask. Paged like
   * every other collection this application answers with (REQ-NFR-010): an instance is unlikely to
   * have two pages of operators, and "unlikely" is not a bound.
   *
   * @param cursor an opaque cursor from a previous page, or null for the first
   * @param limit how many at most, capped at 200
   * @return the page, oldest account first, with a cursor when there is more
   */
  OperatorPage operators(String cursor, int limit);

  /**
   * One page of operators.
   *
   * @param items the accounts
   * @param nextCursor where the next page starts, or null when this was the last
   */
  record OperatorPage(List<AccountView> items, String nextCursor) {}

  /**
   * Replaces what an account is entitled to do on the instance.
   *
   * <p>All three values at once, because they are one operator decision and produce one audit
   * entry. Passing the current value for the ones that are not changing is the caller's job, and it
   * is the reason {@link #byId(UUID)} exists.
   *
   * @param userId the account to change
   * @param instanceOperator whether it administers the instance
   * @param mayCreateTenants whether it may create tenants
   * @param tenantLimit how many tenants it may own, or null for the instance-wide default
   * @param actor the operator making the change
   * @return the account as it now stands
   * @throws de.greluc.homeinv.platform.NotFoundException when there is no such account
   */
  AccountView replaceEntitlements(
      UUID userId,
      boolean instanceOperator,
      boolean mayCreateTenants,
      Integer tenantLimit,
      UUID actor);
}
