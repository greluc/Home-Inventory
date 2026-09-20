/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.portability.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Taking everything with you (REQ-PORT-003, REQ-PORT-004, REQ-PORT-005).
 *
 * <h2>A job, not a response</h2>
 *
 * <p>REQ-PORT-005 asks for {@code 202} plus a job resource with progress, and the reason is
 * ordinary: a tenant with ten thousand items and their photographs is minutes of work and tens of
 * megabytes. A request that held that open would time out on exactly the installations where the
 * feature matters most — and this is the feature somebody uses when they are leaving, which is the
 * worst moment for it to be unreliable.
 *
 * <h2>What is in it</h2>
 *
 * <p>Data, configuration, media and a manifest (REQ-PORT-003), <b>including the type definitions</b>
 * so that a tenant can move entirely (REQ-PORT-004) — an archive of items written against types the
 * receiving instance does not have is an archive of nothing. Each block writes its own share
 * through {@link ExportSource}.
 */
public interface ExportService {

  /**
   * Asks for an export of everything this tenant has.
   *
   * <p>Returns immediately with a job in {@code QUEUED}; the work happens in the worker. Asking
   * twice makes two archives, which is the honest behaviour: the second one is of a later moment,
   * and silently handing back the first would be handing back an archive that is missing whatever
   * changed since.
   *
   * @param actor who asked
   * @return the queued job
   */
  ExportJobView request(UUID actor);

  /**
   * One export job.
   *
   * @param id which one
   * @return it
   * @throws de.greluc.homeinv.platform.NotFoundException when this tenant has no such job
   */
  ExportJobView job(UUID id);

  /**
   * This tenant's export jobs, newest first.
   *
   * @param limit how many at most; capped at 200 (REQ-NFR-010)
   * @return the jobs
   */
  List<ExportJobView> jobs(int limit);

  /**
   * The archive of a finished job.
   *
   * <p>Opened rather than returned: it is tens of megabytes, and a byte array of it would put the
   * ceiling in the wrong place.
   *
   * @param id the job
   * @return the bytes, which the caller closes
   * @throws de.greluc.homeinv.platform.NotFoundException when this tenant has no such job
   * @throws ExportNotReadyException when the job has not finished, or failed
   * @throws java.io.IOException when the store cannot be read
   */
  java.io.InputStream open(UUID id) throws java.io.IOException;

  /**
   * An export job as a caller sees it.
   *
   * @param id its id
   * @param state {@code QUEUED}, {@code RUNNING}, {@code READY} or {@code FAILED}
   * @param progress how far along, 0 to 100 — coarse on purpose, because it is read by somebody
   *     deciding whether to keep waiting and a number that moves is worth more to them than a
   *     number that is exact
   * @param byteSize how large the archive is, or {@code null} until it exists
   * @param failure what went wrong, or {@code null}. Never a stack trace: it is shown to whoever
   *     asked
   * @param requestedAt when it was asked for
   * @param requestedBy who asked
   * @param finishedAt when it finished, or {@code null}
   */
  record ExportJobView(
      UUID id,
      String state,
      int progress,
      Long byteSize,
      String failure,
      Instant requestedAt,
      UUID requestedBy,
      Instant finishedAt) {

    /**
     * Whether the archive can be downloaded.
     *
     * @return {@code true} only in {@code READY}
     */
    public boolean isReady() {
      return "READY".equals(state);
    }
  }
}
