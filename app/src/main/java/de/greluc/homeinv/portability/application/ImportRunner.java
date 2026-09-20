/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.portability.application;

import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.portability.api.ArchiveStore;
import de.greluc.homeinv.portability.api.ImportTarget;
import de.greluc.homeinv.portability.api.Remapping;
import de.greluc.homeinv.portability.infrastructure.ImportJobQueries;
import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads one uploaded archive back into a tenant (REQ-PORT-003, REQ-PORT-004, REQ-PORT-007).
 *
 * <h2>One transaction, and it is the whole import</h2>
 *
 * <p>The opposite arrangement from {@link ExportRunner}, and for the opposite reason. An export
 * must <b>not</b> hold a transaction open, because it is minutes of reading and an idle transaction
 * of that length stops {@code VACUUM} reclaiming anything. An import must, because REQ-PORT-007
 * says it is complete or it never happened — and a half-imported inventory is worse than none: it
 * looks like a success to anybody who was not watching, and nothing says which half arrived.
 *
 * <p>The job row is the one thing written outside it. {@link ImportJobQueries} takes a new
 * transaction for each of its writes, so a failure that rolls the import back still leaves the
 * record of having failed — otherwise the person who asked would watch a job sit in {@code RUNNING}
 * for ever.
 *
 * <h2>A dry run is the same work</h2>
 *
 * <p>Every row is written, every constraint is met or not met, and the transaction is then rolled
 * back on purpose (REQ-PORT-001). A preview produced by different code is a preview of something
 * else, which is the one failure a dry run exists to prevent.
 */
@Slf4j
@Component
public class ImportRunner {

  private final ImportJobQueries jobs;
  private final ArchiveStore blobs;
  private final ObjectMapper json;
  private final List<ImportTarget> targets;
  private final TransactionTemplate transactions;

  /**
   * Creates the runner.
   *
   * @param jobs the SQL layer
   * @param blobs where the uploaded archive is, through a port so nothing points out of this block
   * @param json the mapper
   * @param targets every block that reads part of an archive
   * @param transactions how the one transaction is taken and, for a dry run, given back
   */
  public ImportRunner(
      ImportJobQueries jobs,
      ArchiveStore blobs,
      ObjectMapper json,
      List<ImportTarget> targets,
      TransactionTemplate transactions) {
    this.jobs = jobs;
    this.blobs = blobs;
    this.json = json;
    // Sorted by the order each block declares, not by name: an item written
    // against a type version that has not arrived is a row that cannot be
    // inserted, and a place inside a place that has not arrived is the same
    // problem one level down.
    this.targets = targets.stream().sorted(Comparator.comparingInt(ImportTarget::order)).toList();
    this.transactions = transactions;
  }

  /**
   * Reads every archive waiting for one tenant.
   *
   * <p>Called inside that tenant's context.
   *
   * @param tenantId whose imports
   * @return how many archives were read
   */
  public int runFor(UUID tenantId) {
    int done = 0;
    for (Optional<ImportJobQueries.Claimed> claimed = jobs.claimNext(tenantId);
        claimed.isPresent();
        claimed = jobs.claimNext(tenantId)) {
      run(tenantId, claimed.get());
      done++;
    }
    return done;
  }

  /**
   * The tenant context a run needs, for the caller that spans tenants.
   *
   * @param tenantId whose imports
   * @return how many archives were read
   */
  public int runAsTenant(UUID tenantId) {
    return TenantContext.callAs(tenantId, () -> runFor(tenantId));
  }

  /**
   * Reads one archive into one tenant, or records why it could not be.
   *
   * @param tenantId whose
   * @param job which job
   */
  private void run(UUID tenantId, ImportJobQueries.Claimed job) {
    try {
      ArchiveReader archive;
      try (InputStream bytes = blobs.open(tenantId, job.sha256())) {
        archive = new ArchiveReader(json, bytes);
      }

      Map<String, Object> report = new LinkedHashMap<>();
      report.put("format", archive.manifest().get("format"));
      report.put("exportedFrom", archive.manifest().get("tenantId"));
      report.put("exportedAt", archive.manifest().get("exportedAt"));
      report.put("dryRun", job.dryRun());

      Map<String, Object> blocks = new LinkedHashMap<>();
      transactions.executeWithoutResult(
          status -> {
            // One per import and thrown away with it: what the archive's ids mean
            // here is derived from the natural keys in the archive, not stored.
            Remapping ids = new Remapping();
            int position = 0;
            for (ImportTarget target : targets) {
              ImportTarget.Outcome outcome = target.importFrom(archive, ids);
              blocks.put(
                  target.block(),
                  Map.of(
                      "inserted", outcome.inserted(),
                      "overwritten", outcome.overwritten(),
                      "skipped", outcome.skipped()));
              position++;
              jobs.progress(tenantId, job.id(), position * 95 / Math.max(1, targets.size()));
            }
            if (job.dryRun()) {
              // The whole point: everything above happened, every constraint was
              // met, and none of it stays. REQ-PORT-001 asks for a preview that
              // shows errors without writing, and this is the only way to make
              // one that is a preview OF the import rather than of a second
              // implementation of it.
              status.setRollbackOnly();
            }
          });

      report.put("blocks", blocks);
      report.put("notImported", NOT_IMPORTED);
      jobs.done(tenantId, job.id(), json.writeValueAsString(report));
      log.info(
          "An archive was read into tenant {} ({})",
          tenantId,
          job.dryRun() ? "dry run, rolled back" : "committed");
    } catch (IOException | RuntimeException failed) {
      // Recorded rather than rethrown: one tenant's import failing must not stop
      // the run, and the message is shown to whoever asked -- so it says what
      // happened and never carries a stack trace.
      log.error("An import failed for tenant {}", tenantId, failed);
      jobs.failed(tenantId, job.id(), reasonOf(failed));
    }
  }

  /**
   * What an import never writes, said in the report rather than left to be discovered.
   *
   * <p>Each entry is one sentence a person can act on. The second is the one that surprises people:
   * the field-visibility rules a tenant wrote hang off role definitions, so they do not arrive
   * either, and until somebody sets them again the receiving instance decides who may read a
   * sensitive field by its own defaults.
   */
  private static final List<String> NOT_IMPORTED =
      List.of(
          "Accounts and memberships. An uploaded file must not be able to create accounts on this "
              + "instance, so the people in the archive are not recreated — invite them again.",
          "The roles this tenant defined for itself, and the field-visibility rules attached to "
              + "them. Until they are set again, who may read a field marked sensitive is decided "
              + "by this instance's built-in roles.",
          "Notification channels, which carry somebody's address and belong to the account rather "
              + "than to the inventory.");

  /**
   * What to tell the person who asked.
   *
   * @param failure what went wrong
   * @return one sentence, never a stack trace and never a credential
   */
  private static String reasonOf(Throwable failure) {
    if (failure instanceof org.springframework.dao.DuplicateKeyException) {
      // The one collision an import cannot resolve, and it deserves a sentence
      // rather than a constraint name. An id is unique in the database and not
      // merely within a tenant -- deliberately, because it is the id printed on
      // a label (10 §10.2.1) and a label resolving to two things would be worse
      // than one resolving to none. So an archive lives in one tenant per
      // instance: importing it into a second one while the first still holds
      // those rows is asking for the same id twice.
      return "This archive holds rows whose ids already exist on this instance, in another "
          + "tenant. An archive can be imported into one tenant per instance: the tenant it came "
          + "from still has them. Nothing was written.";
    }
    String detail = failure.getMessage();
    return detail == null || detail.isBlank()
        ? failure.getClass().getSimpleName()
        : detail.substring(0, Math.min(detail.length(), 2000));
  }
}
