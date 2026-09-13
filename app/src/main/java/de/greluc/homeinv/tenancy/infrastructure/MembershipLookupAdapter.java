/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tenancy.infrastructure;

import de.greluc.homeinv.tenancy.api.MembershipLookup;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
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
  private static final String PRIMARY =
      "select tenant_id, role, tenant_name from tenancy.tenants_of_user(?) limit 1";

  /** The same function without the limit: every tenant this person belongs to. */
  private static final String ALL =
      "select tenant_id, role, tenant_name from tenancy.tenants_of_user(?)";

  private final JdbcClient jdbc;

  /**
   * {@inheritDoc}
   *
   * <p>Returns the oldest live membership, which the function orders by creation. At stage 0 there
   * is exactly one. When stage 1 lets a user belong to several, this becomes "the one to start the
   * session in" and the switch (REQ-TEN-003) changes it afterwards.
   */
  @Override
  public Optional<Membership> primaryMembershipOf(UUID userId) {
    return jdbc.sql(PRIMARY).param(userId).query(MembershipLookupAdapter::membershipOf).optional();
  }

  /**
   * {@inheritDoc}
   *
   * <p>The same function, read whole. A person choosing which tenant to act for is doing so without
   * acting for one yet, so this is the one read in the system that cannot go through the policies.
   */
  @Override
  public List<Membership> membershipsOf(UUID userId) {
    return jdbc.sql(ALL).param(userId).query(MembershipLookupAdapter::membershipOf).list();
  }

  /**
   * Maps one row of the lookup function.
   *
   * @param rs the row
   * @param rowNum which row, as the mapper contract takes it
   * @return the membership
   * @throws SQLException when the row cannot be read
   */
  private static Membership membershipOf(ResultSet rs, int rowNum) throws SQLException {
    return new Membership(
        rs.getObject("tenant_id", UUID.class), rs.getString("tenant_name"), rs.getString("role"));
  }
}
