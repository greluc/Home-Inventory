/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.identity.application;

import de.greluc.homeinv.identity.api.AccountAdministration;
import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.platform.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and changes what an account is entitled to on the instance (ADR-0057).
 *
 * <p>Every change is logged at {@code INFO} with the operator, the account and the new values.
 * That is the minimum until the audit log carries instance-level entries of its own: an
 * entitlement grant is the act that makes every later act possible, so an instance whose log cannot
 * say who granted what has no answer to the only question worth asking afterwards.
 *
 * <p><b>Nothing here signs anything</b>, and that is a constraint rather than an observation. The
 * one-shot {@code bootstrap} service depends on this bean, and it runs with the database
 * credentials alone — no URL signing key, because it serves no request. The paged operator listing
 * therefore lives in {@link DefaultOperatorDirectory}, which does need one; {@code BootstrapIsolationTest}
 * fails the build if this chain ever reaches a secret the one-shot is not given.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DefaultAccountAdministration implements AccountAdministration {

  private final AppUserRepository users;
  private final Clock clock;

  @Override
  @Transactional(readOnly = true)
  public Optional<AccountView> byEmail(String email) {
    return users.findByEmail(email).map(DefaultAccountAdministration::viewOf);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<AccountView> byId(UUID userId) {
    return users.findById(userId).map(DefaultAccountAdministration::viewOf);
  }

  @Override
  @Transactional(readOnly = true)
  public boolean hasInstanceOperator() {
    return !users.findInstanceOperators(Limit.of(1)).isEmpty();
  }

  @Override
  @Transactional
  public AccountView replaceEntitlements(
      UUID userId,
      boolean instanceOperator,
      boolean mayCreateTenants,
      Integer tenantLimit,
      UUID actor) {

    AppUser user =
        users.findById(userId).orElseThrow(() -> new NotFoundException("account", userId));
    user.replaceEntitlements(
        instanceOperator, mayCreateTenants, tenantLimit, actor, Instant.now(clock));

    log.info(
        "Operator {} set the entitlements of account {}: operator={}, mayCreateTenants={}, "
            + "tenantLimit={}",
        actor,
        userId,
        instanceOperator,
        mayCreateTenants,
        tenantLimit);
    return viewOf(user);
  }

  /**
   * The operator's view of an account.
   *
   * @param user the entity
   * @return the view, without the credential
   */
  private static AccountView viewOf(AppUser user) {
    return new AccountView(
        user.getId(),
        user.getEmail(),
        user.getDisplayName(),
        user.isInstanceOperator(),
        user.mayCreateTenants(),
        user.getTenantLimit(),
        !user.canAuthenticate());
  }
}
