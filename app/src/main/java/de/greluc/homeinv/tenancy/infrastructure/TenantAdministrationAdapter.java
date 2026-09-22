/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tenancy.infrastructure;

import de.greluc.homeinv.tenancy.api.TenantAdministration;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Suspension, through the two {@code SECURITY DEFINER} functions of {@code V74}.
 *
 * <p>Deliberately not a repository method and not JPA. Both would go through the tenant policy,
 * which returns nothing here because the caller acts for no tenant — and a method that appeared to
 * work would be one somebody later reuses inside a tenant context, where it would quietly do
 * something else. The same reason {@code QuotaAdministrationAdapter} exists beside the quota guard.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TenantAdministrationAdapter implements TenantAdministration {

  private static final String SET = "select tenancy.set_tenant_lifecycle_state(?, ?, ?)";

  private static final String READ = "select tenancy.lifecycle_state_of_tenant(?)";

  private final JdbcClient jdbc;

  @Override
  @Transactional
  public Optional<State> setState(UUID tenantId, State state, UUID actor) {
    Optional<String> result;
    try {
      result =
          jdbc.sql(SET).params(tenantId, state.name(), actor).query(String.class).optional();
    } catch (DataIntegrityViolationException refused) {
      // The function raises `check_violation` for a tenant that is waiting to be
      // erased or already erased. Refused rather than ignored: an immediate
      // measure that silently did nothing is worse than one that fails, because
      // the operator taking it believes it worked.
      throw new IllegalStateException(
          "This tenant is being erased or has been erased, and suspension does not reach that"
              + " state. Withdrawing a deletion request is what the revocation token is for"
              + " (REQ-TEN-011).",
          refused);
    }

    result.ifPresent(
        now ->
            // REQ-SEC-068 wants every mutating action attributable. Until the
            // audit log carries instance-level entries, this line is what says
            // who suspended whom.
            log.warn("Operator {} set tenant {} to {}", actor, tenantId, now));
    return result.map(State::valueOf);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<String> stateOf(UUID tenantId) {
    return jdbc.sql(READ).param(tenantId).query(String.class).optional();
  }
}
