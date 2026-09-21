/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.portability.api;

import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Bringing everything back (REQ-PORT-003, REQ-PORT-004, REQ-PORT-007).
 *
 * <h2>A job, for the same reason the export is one</h2>
 *
 * <p>An archive is tens of megabytes and reading it is minutes of work. The upload answers {@code
 * 202} with a job, and the worker does the reading — so a slow import is a job somebody watches
 * rather than a request that times out halfway through writing.
 *
 * <h2>All of it or none of it</h2>
 *
 * <p>REQ-PORT-007: the whole import is <b>one transaction</b>. A failure at the last row leaves the
 * tenant exactly as it was, because a half-imported inventory is worse than none — it looks like a
 * success to anybody who was not watching, and there is no way to tell which half arrived.
 *
 * <h2>A dry run is the same work, thrown away</h2>
 *
 * <p>{@code dryRun} walks every row, applies every rule and rolls the transaction back at the end
 * (REQ-PORT-001). It is not a separate, simpler code path, because a preview produced by different
 * code is a preview of something else — the one failure a dry run exists to prevent.
 *
 * <h2>People do not travel</h2>
 *
 * <p>An archive carries accounts, memberships and the roles a tenant defined, because Art. 15 asks
 * who had access (REQ-PORT-006). An import writes <b>none</b> of them: a file somebody uploads must
 * not be able to create accounts, and a membership needs an account to point at. The report says so
 * plainly, including the consequence — the field-visibility rules a tenant wrote hang off role
 * definitions, so they do not arrive either, and the receiving instance decides who may read a
 * sensitive field by its own defaults until somebody sets them again.
 */
public interface ImportService {

  /**
   * Accepts an archive and queues it.
   *
   * <p>The bytes are stored and the request returns; nothing about the archive is parsed here,
   * because parsing a file somebody uploaded is work and work in a request is a way to hold one
   * open.
   *
   * @param actor who asked
   * @param archive the uploaded bytes, which this method reads to the end and does not close
   * @param dryRun whether to walk the whole import and then roll it back
   * @param profileKey the mapping profile that reads a CSV — {@code homebox}, {@code inventree} or
   *     a profile the tenant wrote — or null when the upload is this application's own archive
   * @return the queued job
   * @throws java.io.IOException when the upload cannot be stored
   * @throws IllegalArgumentException when no profile has that key
   */
  ImportJobView accept(UUID actor, java.io.InputStream archive, boolean dryRun, String profileKey)
      throws java.io.IOException;

  /**
   * The mapping profiles this tenant may import with (REQ-PORT-002).
   *
   * <p>The two that ship — Homebox and InvenTree — followed by any the tenant wrote.
   *
   * @return the profiles, built-in ones first
   */
  java.util.List<MappingProfile> profiles();

  /**
   * One import job.
   *
   * @param id which one
   * @return it
   * @throws de.greluc.homeinv.platform.NotFoundException when this tenant has no such job
   */
  ImportJobView job(UUID id);

  /**
   * This tenant's import jobs, newest first.
   *
   * @param limit how many at most; capped at 200 (REQ-NFR-010)
   * @return the jobs
   */
  List<ImportJobView> jobs(int limit);

  /**
   * An import job as a caller sees it.
   *
   * @param id its id
   * @param state {@code QUEUED}, {@code RUNNING}, {@code DONE} or {@code FAILED}
   * @param dryRun whether the work was thrown away at the end
   * @param progress how far along, 0 to 100
   * @param byteSize how large the uploaded archive was
   * @param report what happened, as a JSON object, or null until the job finishes. Per block: how
   *     many rows were inserted, how many overwritten and how many deliberately skipped, plus the
   *     sentences saying what an import never writes
   * @param failure what went wrong, or null. One sentence, never a stack trace
   * @param requestedAt when it was uploaded
   * @param finishedAt when it ended, or null
   */
  record ImportJobView(
      UUID id,
      String state,
      boolean dryRun,
      int progress,
      long byteSize,
      @Nullable String report,
      @Nullable String failure,
      Instant requestedAt,
      @Nullable Instant finishedAt) {}
}
