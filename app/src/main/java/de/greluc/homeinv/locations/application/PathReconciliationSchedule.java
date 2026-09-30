/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.locations.application;

import de.greluc.homeinv.locations.infrastructure.PathConsistencyQueries;
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
 * The nightly reconciliation of the location tree (REQ-NFR-073).
 *
 * <h2>What it is watching for</h2>
 *
 * <p>{@code path} is a materialised path: it makes "everything under this place" one index lookup
 * instead of a recursive walk, and it makes the location-scoped roles of REQ-TEN-007 a row policy
 * rather than a join. That speed is bought with a duplicated fact — the tree is in {@code parent_id}
 * and again in {@code path} — and a move that updated a subtree incompletely would leave the two
 * disagreeing, silently, with the scope policy then answering from the stale one.
 *
 * <p>The database refuses the cheap inconsistencies with constraints. What no constraint can express
 * is whose path a row extends, because that needs the parent's row; this is the check for it, and it
 * costs one statement per tenant.
 */
@Slf4j
@Component
@Profile("worker")
@RequiredArgsConstructor
public class PathReconciliationSchedule {

  /** How many deviating places are named in the log line before it stops listing them. */
  private static final int NAMED = 10;

  private final PathConsistencyQueries paths;
  private final MeterRegistry meters;

  /** What the last run found, across every tenant. */
  private final AtomicLong deviations = new AtomicLong();

  /** Registers the gauge, so it exists at zero before the first run rather than after it. */
  @PostConstruct
  void register() {
    Gauge.builder("homeinv.locations.path.deviations", deviations, AtomicLong::get)
        .description(
            "Locations whose materialised path disagrees with their parent, as of the last "
                + "nightly reconciliation (REQ-NFR-073)")
        .register(meters);
  }

  /** Compares the path with the parent, for every tenant that owns a location. */
  @Scheduled(
      fixedDelayString = "${HOMEINV_RECONCILIATION_INTERVAL_MS:86400000}",
      initialDelay = 150_000)
  public void reconcile() {
    try {
      long total = 0;
      for (UUID tenantId : paths.tenantsWithLocations()) {
        long found = TenantContext.callAs(tenantId, paths::deviations);
        if (found > 0) {
          log.warn(
              "The location path reconciliation found {} deviating place(s) in tenant {}: {}",
              found,
              tenantId,
              TenantContext.callAs(tenantId, () -> paths.deviating(NAMED)));
        }
        total += found;
      }
      deviations.set(total);
      log.info("The location path reconciliation finished with {} deviation(s)", total);
    } catch (RuntimeException failed) {
      log.error("The location path reconciliation failed; the next run picks it up", failed);
    }
  }
}
