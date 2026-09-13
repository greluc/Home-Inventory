/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.crypto.infrastructure;

import de.greluc.homeinv.platform.TenantErasure;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Removes a tenant's data keys with the tenant (REQ-TEN-011).
 *
 * <p>Last of the blocks that remove anything, and that position is the point: a key deleted while a
 * sealed value still referenced it would leave a value nothing could ever open — which is the same
 * outcome as an erasure, reached in a way nobody could tell from a bug. Deleted after the data, it
 * is the last thing to go and nothing is left pointing at it.
 *
 * <p>It is also the strongest part of the erasure. Even a backup taken before the run holds only
 * ciphertext once the key is gone from every later one.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class CryptoErasure implements TenantErasure {

  private final JdbcClient jdbc;

  /**
   * {@inheritDoc}
   *
   * <p>After every block that holds sealed values — {@code inventory} at 30, {@code locations} at
   * 50, {@code catalog} at 60 — and before the audit log's report at 90, which removes nothing.
   */
  @Override
  public int order() {
    return 80;
  }

  @Override
  public String block() {
    return "crypto";
  }

  @Override
  @Transactional
  public BlockReport erase(UUID tenantId) {
    long removed = jdbc.sql("delete from crypto.tenant_data_key").update();
    log.info("Erased {} rows of block {} for tenant {}", removed, block(), tenantId);
    return new BlockReport(block(), removed, null);
  }
}
