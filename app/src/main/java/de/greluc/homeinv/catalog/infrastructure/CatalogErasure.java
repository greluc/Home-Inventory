/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.catalog.infrastructure;

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
 * <p>Field definitions first: they point at a type version and at a value list, and both of those
 * point at the type or list above them. Versions before their types, entries before their lists.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class CatalogErasure implements TenantErasure {

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
          "delete from catalog.field_definition",
          "delete from catalog.value_list_entry",
          "delete from catalog.value_list",
          "delete from catalog.item_type_version",
          "delete from catalog.item_type",
          "delete from catalog.location_category_version",
          "delete from catalog.location_category");

  private final JdbcClient jdbc;

  /**
   * {@inheritDoc}
   *
   * <p>Late, because everything that names a type, a category or a value list has to be gone
   * first: items, locations and the field definitions this block holds itself.
   */
  @Override
  public int order() {
    return 60;
  }

  @Override
  public String block() {
    return "catalog";
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
