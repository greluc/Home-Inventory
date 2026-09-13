/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.idempotency.infrastructure;

import de.greluc.homeinv.platform.TenantErasure;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Removes a tenant's idempotency records with the tenant (REQ-TEN-011).
 *
 * <p>They hold the answers this tenant's clients were given, which is tenant data however
 * infrastructural the table is. It is also the only thing that ever removes the records of a tenant
 * that stopped writing: expiry happens on the path that spends a key, and a tenant that spends none
 * has none to expire.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class IdempotencyErasure implements TenantErasure {

  private final JdbcClient jdbc;

  /**
   * {@inheritDoc}
   *
   * <p>First, and free to be: nothing points at these rows and they point at nothing. Before the
   * blocks whose answers they quote, so that a report reads in the order a person would tell it.
   */
  @Override
  public int order() {
    return 5;
  }

  @Override
  public String block() {
    return "idempotency";
  }

  /**
   * {@inheritDoc}
   *
   * <p>Its own transaction, and not the runner's: {@code app.tenant_id} is published when a
   * transaction begins, so a delete outside one runs with no tenant set and the policy removes
   * nothing.
   */
  @Override
  @Transactional
  public BlockReport erase(UUID tenantId) {
    long removed = jdbc.sql("delete from idempotency.processed_request").update();
    log.info("Erased {} rows of block {} for tenant {}", removed, block(), tenantId);
    return new BlockReport(block(), removed, null);
  }
}
