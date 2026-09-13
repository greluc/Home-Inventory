/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tenancy.infrastructure;

import de.greluc.homeinv.tenancy.api.QuotaAdministration;
import de.greluc.homeinv.tenancy.api.QuotaGuard;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The instance operator's quota writes, through the definer functions of migration {@code V24}.
 *
 * <p>Deliberately not a repository method and not JPA. Both would go through the tenant policy,
 * which returns nothing here because the caller acts for no tenant — and a method that appeared to
 * work would be one somebody later reuses inside a tenant context, where it would quietly do
 * something else. The two paths are kept visibly different because they are different, which is the
 * same reason {@code MembershipLookupAdapter} exists beside {@code MembershipRepository}.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class QuotaAdministrationAdapter implements QuotaAdministration {

  private static final String LIMITS = "select quota, permitted from tenancy.quotas_of_tenant(?)";

  private static final String SET = "select tenancy.set_tenant_quota(?, ?, ?, ?)";

  private final JdbcClient jdbc;

  @Override
  @Transactional(readOnly = true)
  public List<QuotaLimit> limitsOf(UUID tenantId) {
    return jdbc
        .sql(LIMITS)
        .param(tenantId)
        .query(
            (rs, rowNum) ->
                new QuotaLimit(
                    QuotaGuard.Quota.valueOf(rs.getString("quota")), rs.getLong("permitted")))
        .list();
  }

  @Override
  @Transactional
  public void setLimit(UUID tenantId, QuotaGuard.Quota quota, long permitted, UUID actor) {
    if (permitted < 0) {
      throw new IllegalArgumentException("A quota is zero or more");
    }
    jdbc.sql(SET).params(tenantId, quota.name(), permitted, actor).query(String.class).optional();
    // REQ-SEC-068 wants every mutating action attributable. Until the audit log
    // carries instance-level entries, this line is what says who allocated what.
    log.info(
        "Operator {} set the {} quota of tenant {} to {}", actor, quota, tenantId, permitted);
  }
}
