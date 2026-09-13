/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tenancy.infrastructure;

import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Finds which tenant a revocation token belongs to, before any tenant context exists.
 *
 * <p>The third read in this application that runs without {@code app.tenant_id}, after the login's
 * membership lookup and the invitation's, and it exists for a sharper version of the same reason:
 * whoever follows a revocation link <b>cannot</b> sign in, because the pending deletion is
 * precisely what stopped them.
 *
 * <p>It goes through {@code tenancy.tenant_by_revocation_token}, the {@code SECURITY DEFINER}
 * function of migration {@code V31} — the way out 07 §7.5 names, rather than {@code BYPASSRLS},
 * which would apply to every query the process ever makes.
 *
 * <p>The function does <b>not</b> filter on state. A token for a request already withdrawn is
 * answered exactly like one that never existed, and doing that filtering here rather than in the
 * function is what keeps the two indistinguishable from outside.
 */
@Component
@RequiredArgsConstructor
public class TenantRevocationLookup {

  private static final String QUERY =
      "select tenant_id from tenancy.tenant_by_revocation_token(?)";

  private final JdbcClient jdbc;

  /**
   * Which tenant a token hash names.
   *
   * @param tokenHash the SHA-256 of the presented token, in lower-case hexadecimal
   * @return the tenant, or empty when no tenant has that hash
   */
  public Optional<UUID> locate(String tokenHash) {
    return jdbc.sql(QUERY).param(tokenHash).query(UUID.class).optional();
  }
}
