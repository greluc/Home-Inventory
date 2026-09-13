/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.infrastructure;

import de.greluc.homeinv.platform.TenantErasure;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Removes this block's share of a tenant (REQ-TEN-011).
 *
 * <p>The index side table and the relations go before the items they belong to: both reference
 * {@code inventory.item}, and the database refuses the other order.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class InventoryErasure implements TenantErasure {

  /**
   * The deletions, in the order a foreign key allows.
   *
   * <p>Children before parents. The database would refuse the other order and say so, but a refusal
   * mid-erasure leaves a tenant half gone, and the order is cheaper to get right here than to
   * recover from there.
   *
   * <p>Each statement is written out whole rather than a verb joined to a table name. The rule that
   * keeps SQL out of string concatenation admits no exception for a constant (REQ-SEC-031), because
   * the reader after next is the one who joins a parameter to it.
   */
  private static final List<String> DELETES =
      List.of(
          "delete from inventory.item_attr_index",
          "delete from inventory.item_bundle",
          "delete from inventory.item_relation",
          "delete from inventory.item");

  private final JdbcClient jdbc;

  /**
   * {@inheritDoc}
   *
   * <p>Before {@code locations} and {@code catalog}: {@code inventory.item} references a place
   * and a type version, and neither reference cascades. This is the order the database enforced
   * the first time the run was tried the other way round.
   */
  @Override
  public int order() {
    return 30;
  }

  @Override
  public String block() {
    return "inventory";
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
      // No tenant predicate: the context is established and the policy scopes
      // the delete. A predicate here would be a second place the tenant is
      // decided, and the one that is wrong is always the second.
      removed += jdbc.sql(statement).update();
    }
    log.info("Erased {} rows of block {} for tenant {}", removed, block(), tenantId);
    return new BlockReport(block(), removed, null);
  }
}
