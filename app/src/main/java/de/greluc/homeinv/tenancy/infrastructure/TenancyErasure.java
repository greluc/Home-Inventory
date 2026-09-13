/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tenancy.infrastructure;

import de.greluc.homeinv.platform.TenantErasure;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Removes what the tenant block itself holds, and tombstones the tenant (REQ-TEN-011).
 *
 * <p>What goes is everything the tenant row carried about people: the memberships, the invitations
 * and their tokens, the quotas and their counters.
 *
 * <p>The tenant row itself stays, as a tombstone. The erasure certificate names the tenant, and a
 * certificate pointing at an id nothing answers for would be evidence of an erasure that cannot say
 * what was erased. Marking it is not this block's job either: the run does it after every block has
 * reported, because a tenant marked erased is one {@code tenancy.tenants_due_for_erasure} no longer
 * returns, and a run interrupted before that mark is one the next sweep finishes.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TenancyErasure implements TenantErasure {

  /**
   * Everything this block holds about a tenant except the tenant itself.
   *
   * <p>Each statement is written out whole rather than a verb joined to a table name. The rule that
   * keeps SQL out of string concatenation admits no exception for a constant (REQ-SEC-031), because
   * the reader after next is the one who joins a parameter to it.
   */
  private static final List<String> DELETES =
      List.of(
          "delete from tenancy.invitation",
          "delete from tenancy.membership",
          "delete from tenancy.quota_usage",
          "delete from tenancy.tenant_quota");

  private final JdbcClient jdbc;

  /**
   * {@inheritDoc}
   *
   * <p>Before {@code locations} and {@code authorization}, and that is why it is not last:
   * {@code tenancy.membership} references a location a member is confined to (V28) and the
   * tenant-owned role they hold (V26). What used to be last is the tenant row itself, and the run
   * tombstones that after every block has reported.
   */
  @Override
  public int order() {
    return 40;
  }

  @Override
  public String block() {
    return "tenancy";
  }

  /**
   * {@inheritDoc}
   *
   * <p>Its own transaction, and not the runner's. {@code app.tenant_id} is published when a
   * transaction begins, so a delete outside one runs with no tenant set and the policy removes
   * nothing — and a block that failed would otherwise roll back every block before it.
   */
  @Override
  @Transactional
  public BlockReport erase(UUID tenantId) {
    long removed = 0;
    for (String statement : DELETES) {
      removed += jdbc.sql(statement).update();
    }

    log.info("Erased {} rows of block tenancy for tenant {}", removed, tenantId);
    return new BlockReport(
        block(),
        removed,
        "The tenant row itself is kept as a tombstone, because this certificate names it. It"
            + " carries the id, the name above and the counts, and nothing else.");
  }
}
