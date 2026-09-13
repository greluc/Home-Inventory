/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.identity.infrastructure;

import de.greluc.homeinv.identity.domain.ServiceAccount;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence for service accounts (REQ-AUTH-010).
 *
 * <p>Tenant-scoped, so every query here runs under a policy: the tenant predicate below is what the
 * application layer asks for, and the policy is what holds when the application layer is wrong
 * (ADR-0003). The one lookup that cannot go this way is by token, which happens before any tenant
 * is known — {@link ServiceAccountTokens} does that one.
 */
public interface ServiceAccountRepository extends JpaRepository<ServiceAccount, UUID> {

  /**
   * The tenant's live service accounts, newest first.
   *
   * @param tenantId the tenant, which the policy scopes to anyway
   * @param limit how many at most (REQ-NFR-010)
   * @return the accounts
   */
  @Query("select s from ServiceAccount s where s.tenantId = :tenantId and s.deletedAt is null"
      + " order by s.createdAt desc, s.id desc")
  List<ServiceAccount> findLive(@Param("tenantId") UUID tenantId, Limit limit);
}
