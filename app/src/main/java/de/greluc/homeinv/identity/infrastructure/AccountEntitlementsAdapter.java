/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.identity.infrastructure;

import de.greluc.homeinv.authorization.api.AccountEntitlements;
import de.greluc.homeinv.authorization.api.Entitlement;
import de.greluc.homeinv.identity.domain.AppUser;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers {@code authorization}'s questions about an account (ADR-0057).
 *
 * <p>The port is declared in {@code authorization} and implemented here, which is the direction
 * that keeps the two blocks acyclic: this block already depends on that one's vocabulary, and a
 * call the other way would close a cycle.
 *
 * <p>A locked or deleted account holds nothing, whatever its columns say. That is not defensive
 * duplication of the login check: locking an account is how an operator stops somebody acting, and
 * an entitlement that survived it would be an entitlement the lock does not reach — including the
 * one that grants entitlements.
 */
@Component
@RequiredArgsConstructor
public class AccountEntitlementsAdapter implements AccountEntitlements {

  private final AppUserRepository users;

  @Override
  @Transactional(readOnly = true)
  public boolean holds(UUID userId, Entitlement entitlement) {
    return account(userId)
        .filter(AppUser::canAuthenticate)
        .map(
            user ->
                switch (entitlement) {
                  case INSTANCE_OPERATOR -> user.isInstanceOperator();
                  case CREATE_TENANT -> user.mayCreateTenants();
                })
        .orElse(false);
  }

  @Override
  @Transactional(readOnly = true)
  public OptionalInt tenantLimit(UUID userId) {
    return account(userId)
        .map(AppUser::getTenantLimit)
        .map(OptionalInt::of)
        .orElseGet(OptionalInt::empty);
  }

  /**
   * The account, if there is one.
   *
   * <p>{@code identity.app_user} is instance-wide and carries no row-level security, so this is a
   * plain read by primary key. What keeps it from being an enumeration surface is that the id has
   * to come from somewhere — a session, or a membership of the caller's own tenant — and neither is
   * reachable without already being entitled to it (07 §7.1).
   *
   * @param userId the account's id
   * @return the account, or empty
   */
  private Optional<AppUser> account(UUID userId) {
    return userId == null ? Optional.empty() : users.findById(userId);
  }
}
