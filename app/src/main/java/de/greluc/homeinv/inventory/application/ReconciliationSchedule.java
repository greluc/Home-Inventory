/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.application;

import de.greluc.homeinv.inventory.infrastructure.AttributeIndexQueries;
import de.greluc.homeinv.platform.TenantContext;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The nightly reconciliation of the attribute index, and the number it publishes (REQ-NFR-073).
 *
 * <h2>Why a metric and not an alert here</h2>
 *
 * <p>Because the right response depends on the number. One deviating item after a release is a bug
 * to find; a thousand is a rebuild to run. What this owes an operator is the count, over time, in
 * the same place as every other number they watch (13 §13.7) — and a gauge that is zero every night
 * is how "the projection is exact" stops being a claim.
 *
 * <p><b>Instance-wide, and one number.</b> A gauge per tenant would be a series per tenant, and
 * REQ-NFR-010's cardinality argument applies to metrics as much as to pages: an installation with
 * two thousand tenants would pay for that in Prometheus for ever. Which tenant it is appears in the
 * log line beside it, which is where somebody looks once the gauge is not zero.
 */
@Slf4j
@Component
@Profile("worker")
@RequiredArgsConstructor
public class ReconciliationSchedule {

  private final AttributeIndexQueries tenants;
  private final AttributeIndexReconciliation reconciliation;
  private final MeterRegistry meters;

  /** What the last run found, across every tenant. */
  private final AtomicLong deviations = new AtomicLong();

  /** Registers the gauge, so it exists at zero before the first run rather than after it. */
  @PostConstruct
  void register() {
    Gauge.builder("homeinv.inventory.attribute_index.deviations", deviations, AtomicLong::get)
        .description(
            "Items whose item_attr_index rows disagree with item.attributes, as of the last "
                + "nightly reconciliation (REQ-NFR-073)")
        .register(meters);
  }

  /**
   * Compares the projection with the truth, for every tenant that owns an item.
   *
   * <p>Nightly. The run is read-only and repairs nothing: a reconciliation that fixed what it found
   * would report zero for ever and hide the defect that produced it (ADR-0004).
   */
  @Scheduled(
      fixedDelayString = "${HOMEINV_RECONCILIATION_INTERVAL_MS:86400000}",
      initialDelay = 120_000)
  public void reconcile() {
    try {
      long total = 0;
      for (UUID tenantId : tenants.tenantsWithItems()) {
        long found = TenantContext.callAs(tenantId, reconciliation::deviations);
        if (found > 0) {
          log.warn(
              "The attribute index reconciliation found {} deviating item(s) in tenant {}. A "
                  + "REINDEX_ATTRIBUTES run rebuilds the table from item.attributes.",
              found,
              tenantId);
        }
        total += found;
      }
      deviations.set(total);
      log.info("The attribute index reconciliation finished with {} deviation(s)", total);
    } catch (RuntimeException failed) {
      // Logged and swallowed, like every other run here: a task that throws stops
      // being scheduled in some runtimes, and a reconciliation that silently
      // stopped running is worse than one that reports a problem.
      log.error("The attribute index reconciliation failed; the next run picks it up", failed);
    }
  }
}
