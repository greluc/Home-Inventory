/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tenancy.infrastructure;

import de.greluc.homeinv.tenancy.api.MembershipLookup;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Answers "which tenant does this person act for" during login, before a tenant context exists.
 *
 * <p>This is the one place in the application that reads tenant data without a tenant set, and it
 * does so through {@code tenancy.tenants_of_user}, the {@code SECURITY DEFINER} function introduced
 * by migration {@code V7}. The alternative would be granting the application {@code BYPASSRLS},
 * which {@code 07 §7.5} rules out — and rightly: a privilege taken for one lookup applies to every
 * query the process ever makes.
 *
 * <p>Deliberately not a Spring Data repository method. {@code MembershipRepository} goes through
 * JPA and therefore through the policy, which returns nothing here; a method on it that appeared to
 * work would be one somebody later reuses inside a tenant context, where it would quietly return
 * a different set. The two paths are kept visibly different because they are different.
 */
@Component
@RequiredArgsConstructor
public class MembershipLookupAdapter implements MembershipLookup {

  // LIMIT 1, not `.optional()` on the whole result: the function returns every
  // membership in order, and `optional()` throws when there is more than one row.
  // At stage 0 that never happens, which is exactly why it would be found later.
  private static final String QUERY = "select tenant_id from tenancy.tenants_of_user(?) limit 1";

  private final JdbcClient jdbc;

  /**
   * {@inheritDoc}
   *
   * <p>Returns the oldest live membership's tenant, which the function orders by creation. At stage
   * 0 there is exactly one. When stage 1 lets a user belong to several, this becomes "the one to
   * start the session in" and the switch (REQ-TEN-003) changes it afterwards.
   */
  @Override
  public Optional<UUID> primaryTenantOf(UUID userId) {
    return jdbc.sql(QUERY).param(userId).query(UUID.class).optional();
  }
}
