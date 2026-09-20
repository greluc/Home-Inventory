/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.portability.application;

import de.greluc.homeinv.portability.api.ArchiveStore;
import de.greluc.homeinv.platform.NotFoundException;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.portability.api.ExportNotReadyException;
import de.greluc.homeinv.portability.api.ExportService;
import de.greluc.homeinv.portability.infrastructure.ExportJobQueries;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Export jobs (REQ-PORT-005).
 *
 * <p>Thin, and deliberately so: everything interesting happens in {@link ExportRunner}, in the
 * worker. What this owns is the <b>promise</b> — a job exists, it has a state, and it can be asked
 * about — which is what makes the answer to a request a {@code 202} rather than a long wait.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultExportService implements ExportService {

  /** The cap REQ-NFR-010 puts on every collection. */
  private static final int MAX_JOBS = 200;

  private final ExportJobQueries jobs;
  private final ArchiveStore blobs;

  @Override
  @Transactional
  public ExportJobView request(UUID actor) {
    UUID tenantId = TenantContext.require();
    // Asking twice makes two archives, and that is the honest behaviour: the
    // second is of a later moment, and handing back the first would hand back an
    // archive missing whatever changed since.
    UUID id = jobs.queue(tenantId, actor);
    log.info("An export was requested for tenant {}", tenantId);
    return jobs.byId(tenantId, id).orElseThrow();
  }

  @Override
  @Transactional(readOnly = true)
  public ExportJobView job(UUID id) {
    return jobs
        .byId(TenantContext.require(), id)
        .orElseThrow(() -> new NotFoundException("export job", id));
  }

  @Override
  @Transactional(readOnly = true)
  public List<ExportJobView> jobs(int limit) {
    return jobs.all(TenantContext.require(), Math.clamp(limit, 1, MAX_JOBS));
  }

  @Override
  @Transactional(readOnly = true)
  public InputStream open(UUID id) throws IOException {
    UUID tenantId = TenantContext.require();
    ExportJobView view =
        jobs.byId(tenantId, id).orElseThrow(() -> new NotFoundException("export job", id));
    if (!view.isReady()) {
      // Not a 404: the caller is looking at the right thing and it is simply not
      // finished, which is a different next move from "there is no such job".
      throw new ExportNotReadyException(view.state().toLowerCase(java.util.Locale.ROOT));
    }
    String sha256 =
        jobs.archiveOf(tenantId, id).orElseThrow(() -> new NotFoundException("export job", id));
    return blobs.open(tenantId, sha256);
  }
}
