/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.media.application;

import de.greluc.homeinv.media.api.DeploymentBlobStore;
import de.greluc.homeinv.media.domain.UploadSession;
import de.greluc.homeinv.media.infrastructure.ExpiredUploadQueries;
import de.greluc.homeinv.media.infrastructure.UploadSessionRepository;
import de.greluc.homeinv.platform.TenantContext;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * Removes uploads that were begun and never finished (REQ-MED-008).
 *
 * <p>An upload nobody came back for is a file occupying the volume for ever. There is no user
 * action that removes one — a client that gives up simply stops — so something has to, and this is
 * it.
 *
 * <p><b>The bytes go before the row</b>, which is the opposite of the order the completion uses and
 * right for the same reason: here the row is the only record of where the bytes are, so deleting it
 * first would leave a staged file nothing knows how to find. `OrphanedBlobSweep` makes the same
 * choice for the same trade — a failure costs a retry, never an unreachable file.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExpiredUploadSweep {

  /** How many uploads one tenant may lose in one pass, so a large clear-out stays bounded. */
  private static final int BATCH = 200;

  private final ExpiredUploadQueries expired;
  private final UploadSessionRepository sessions;
  private final DeploymentBlobStore staging;
  private final Clock clock;

  /**
   * Removes every unfinished upload whose time is up.
   *
   * @return how many were removed
   */
  public int sweep() {
    Instant now = Instant.now(clock);
    int removed = 0;
    for (UUID tenantId : expired.tenantsWithExpiredUploads(now)) {
      // The context around the work, so every statement below runs under it:
      // `SET LOCAL app.tenant_id` is applied when each transaction begins, from
      // the context current at that moment.
      removed += TenantContext.callAs(tenantId, () -> sweepTenant(tenantId, now));
    }
    if (removed > 0) {
      log.info("The expired upload sweep removed {} unfinished upload(s)", removed);
    }
    return removed;
  }

  /**
   * Sweeps one tenant, in that tenant's context.
   *
   * <p><b>Deliberately not one transaction</b>, and not merely un-annotated: the staged bytes are
   * removed from a service over mTLS and the row afterwards, and no database transaction rolls back
   * a deleted file. Each statement gets its own transaction from the repository, inside the tenant
   * context the caller established — which is what makes the failure mode the recoverable one.
   * `OrphanedBlobSweep` carries the same note for the same reason.
   *
   * @param tenantId whose uploads
   * @param now the moment an upload must have expired before
   * @return how many were removed
   */
  public int sweepTenant(UUID tenantId, Instant now) {
    List<UploadSession> due = sessions.findExpired(now, PageRequest.of(0, BATCH));
    int removed = 0;
    for (UploadSession session : due) {
      try {
        staging.deleteStaged(tenantId, session.getId());
      } catch (IOException failed) {
        // The row stays, so the next run tries again. Removing it here would
        // leave staged bytes with nothing left that knows their address.
        log.warn("A staged upload could not be removed; the next run will try again", failed);
        continue;
      }
      sessions.delete(session);
      removed++;
    }
    return removed;
  }
}
