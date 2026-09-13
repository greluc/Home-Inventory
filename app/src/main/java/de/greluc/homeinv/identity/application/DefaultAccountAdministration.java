/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.identity.application;

import de.greluc.homeinv.identity.api.AccountAdministration;
import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.platform.CursorCodec;
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
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DefaultAccountAdministration implements AccountAdministration {

  /** The cap REQ-NFR-010 puts on every page of every collection. */
  private static final int MAX_PAGE = 200;

  /** What an operator cursor is bound to, so it cannot be replayed against another listing. */
  private static final String CURSOR = "instance-operators";

  private final AppUserRepository users;
  private final CursorCodec cursors;
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
  public OperatorPage operators(String cursor, int limit) {
    int size = Math.clamp(limit, 1, MAX_PAGE);
    List<AppUser> rows;
    if (cursor == null || cursor.isBlank()) {
      rows = users.findInstanceOperators(Limit.of(size));
    } else {
      CursorCodec.Position from = cursors.decode(cursor, CURSOR);
      rows = users.findInstanceOperatorsAfter(from.createdAt(), from.id(), Limit.of(size));
    }

    String next = null;
    if (rows.size() == size) {
      AppUser last = rows.getLast();
      next = cursors.encode(new CursorCodec.Position(last.getCreatedAt(), last.getId()), CURSOR);
    }
    return new OperatorPage(
        rows.stream().map(DefaultAccountAdministration::viewOf).toList(), next);
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
