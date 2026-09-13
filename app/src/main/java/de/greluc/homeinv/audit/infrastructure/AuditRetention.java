/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.audit.infrastructure;

import de.greluc.homeinv.platform.TenantErasure;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The audit log's entry in an erasure report — which is that it stays (REQ-TEN-011, REQ-SEC-069).
 *
 * <h2>Why this removes nothing</h2>
 *
 * <p>{@code REQ-SEC-069} gives the application {@code INSERT} and {@code SELECT} on the audit log
 * and nothing more; deletions run under {@code homeinv_housekeeping}, which is a separate role the
 * application does not hold (ADR-0046). A block here that tried would be refused by the database,
 * and a block that quietly skipped would leave a certificate implying the log had gone.
 *
 * <p>So it reports, with a count and a reason. The log is removed by the retention run that
 * {@code REQ-PRIV-010} governs — within the tenant's configured period, bounded between 30 and 365
 * days — and the certificate says exactly that rather than leaving a reader to assume either way.
 *
 * <p>It still counts what is there, because "nothing was removed" and "there was nothing" are
 * different facts and a certificate that could not tell them apart would be worth less.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AuditRetention implements TenantErasure {

  /** What remains, so the report can say how much rather than only that some does. */
  private static final String COUNT = "select count(*) from audit.revision_record";

  private final JdbcClient jdbc;

  /**
   * {@inheritDoc}
   *
   * <p>Last, and free to be: it removes nothing, so no foreign key has an opinion about where it
   * goes. Last is where it says the most — a certificate that ends with what was deliberately kept
   * reads as one rather than as a list that ran out.
   */
  @Override
  public int order() {
    return 90;
  }

  @Override
  public String block() {
    return "audit";
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
    long remaining = jdbc.sql(COUNT).query(Long.class).single();
    log.info(
        "The audit log of tenant {} keeps {} entries; the retention run removes them "
            + "(REQ-PRIV-010).",
        tenantId,
        remaining);
    return new BlockReport(
        block(),
        0,
        remaining
            + " entries are retained. The application may not delete audit records (REQ-SEC-069);"
            + " they are removed by the retention run under homeinv_housekeeping, within the"
            + " tenant's retention period (REQ-PRIV-010).");
  }
}
