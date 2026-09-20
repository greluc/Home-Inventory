/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.media.application;

import de.greluc.homeinv.media.api.BlobStore;
import de.greluc.homeinv.media.infrastructure.OrphanQueries;
import de.greluc.homeinv.platform.TenantContext;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Removes blobs nothing points at any more (REQ-MED-011, 13 §13.8).
 *
 * <h2>Never immediately</h2>
 *
 * <p>Detaching a photograph from one item and attaching it to another is two operations, and
 * between them the reference count is zero. Deleting the bytes the moment it hits zero would remove
 * the picture somebody is in the middle of moving — and, because identical uploads are deduplicated
 * per tenant (ADR-0032), that one blob may be the copy several items would have shared. So the
 * count reaching zero starts a <b>grace period</b>, and only what has been unreferenced for longer
 * than that is removed.
 *
 * <h2>The row goes after the bytes, and only if the bytes went</h2>
 *
 * <p>A row without its blob is a photograph that 404s; a blob without its row is bytes nothing will
 * ever reclaim. Neither is good and they are not equally bad: the second is recoverable by running
 * this again with a fixed store, the first is a broken page for ever. So the blob is deleted first
 * and the row only once that succeeded, which means a failure here leaves the pair intact and the
 * next run picks it up.
 */
@Slf4j
@Component
public class OrphanedBlobSweep {

  /** How many blobs one tenant may lose in one pass, so a large clear-out stays bounded. */
  private static final int BATCH = 500;

  private final OrphanQueries orphans;
  private final BlobStore blobs;
  private final Clock clock;
  private final Duration grace;

  /**
   * Creates the sweep.
   *
   * @param orphans the SQL layer
   * @param blobs where the bytes are
   * @param clock the clock, so a test does not have to wait a week
   * @param graceDays how long a blob stays after the last reference goes. Seven days by default,
   *     which is longer than any plausible detach-and-reattach and shorter than anybody would
   *     notice the space
   */
  public OrphanedBlobSweep(
      OrphanQueries orphans,
      BlobStore blobs,
      Clock clock,
      @Value("${HOMEINV_BLOB_GRACE_DAYS:7}") int graceDays) {
    this.orphans = orphans;
    this.blobs = blobs;
    this.clock = clock;
    this.grace = Duration.ofDays(graceDays);
  }

  /**
   * Sweeps every tenant that has something to sweep.
   *
   * @return how many blobs were removed
   */
  public int sweep() {
    Instant before = Instant.now(clock).minus(grace);
    int removed = 0;
    for (UUID tenantId : orphans.tenantsWithOrphans(before)) {
      // The context around the transaction and never inside it, which is the
      // shape `DeliveryDispatcher` uses: `SET LOCAL app.tenant_id` is applied
      // when the transaction begins, from the context current at that moment.
      removed += TenantContext.callAs(tenantId, () -> sweepTenant(tenantId, before));
    }
    if (removed > 0) {
      log.info("The orphaned blob sweep removed {} blob(s)", removed);
    }
    return removed;
  }

  /**
   * Sweeps one tenant, in that tenant's context.
   *
   * @param tenantId whose blobs
   * @param before the moment a blob must have been unreferenced since to go
   * @return how many were removed
   */
  @Transactional
  public int sweepTenant(UUID tenantId, Instant before) {
    List<OrphanQueries.Orphan> due = orphans.orphansOf(before, BATCH);
    int removed = 0;
    for (OrphanQueries.Orphan orphan : due) {
      try {
        // The bytes first. A row whose blob is gone is a photograph that 404s
        // for ever; bytes whose row is gone are reclaimed by the next run.
        blobs.delete(tenantId, orphan.sha256());
      } catch (IOException unreachable) {
        // Logged and skipped, not rethrown: one unreachable blob must not stop
        // the rest of the sweep, and the row stays so the next run tries again.
        log.warn(
            "An orphaned blob could not be removed from the store; it stays for the next run",
            unreachable);
        continue;
      }
      orphans.forget(tenantId, orphan.id());
      removed++;
    }
    return removed;
  }
}
