/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.portability.application;

import de.greluc.homeinv.platform.CallerContext;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.portability.api.ArchiveStore;
import de.greluc.homeinv.portability.api.ExportSource;
import de.greluc.homeinv.portability.infrastructure.ExportJobQueries;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Builds one tenant's export archive (REQ-PORT-003, REQ-PORT-004, REQ-PORT-005).
 *
 * <h2>Outside a transaction, on purpose</h2>
 *
 * <p>A build is minutes of work and tens of megabytes. Holding a database transaction open for it
 * would hold a row lock for it too, and an idle transaction of that length is the thing that makes
 * a `VACUUM` unable to reclaim anything. The job is <b>claimed</b> in one short transaction, built
 * outside of any, and its outcome recorded in another — so a worker that dies mid-build leaves the
 * job in {@code RUNNING} rather than holding a lock, and the operator can see exactly that.
 *
 * <h2>Every block writes its own share</h2>
 *
 * <p>Through {@link ExportSource}, in a stable order: the archive of the same tenant built twice
 * should differ only where the data did. Sorting by block name is arbitrary and it is
 * <i>consistent</i>, which is the property that matters for anybody diffing two archives.
 */
@Slf4j
@Component
public class ExportRunner {

  private final ExportJobQueries jobs;
  private final ArchiveStore blobs;
  private final ObjectMapper json;
  private final Clock clock;
  private final List<ExportSource> sources;
  private final String version;
  private final String commit;

  /**
   * Creates the runner.
   *
   * @param jobs the SQL layer
   * @param blobs where the finished archive goes, through a port so nothing points out of this block
   * @param json the mapper, so the archive reads like the API does
   * @param clock the clock
   * @param sources every block that has something to contribute
   * @param version the application version, recorded in the manifest
   * @param commit the exact build, recorded in the manifest — two builds of one version can differ,
   *     which is the same reason `REQ-CON-009`'s footer names it
   */
  public ExportRunner(
      ExportJobQueries jobs,
      ArchiveStore blobs,
      ObjectMapper json,
      Clock clock,
      List<ExportSource> sources,
      @Value("${build.version:unknown}") String version,
      @Value("${build.commit:unknown}") String commit) {
    this.jobs = jobs;
    this.blobs = blobs;
    this.json = json;
    this.clock = clock;
    // Sorted once here rather than per run: the order is part of what the archive
    // is, and an order that came from a classpath scan would change under a
    // rebuild for no reason anybody could see.
    this.sources = sources.stream().sorted(Comparator.comparing(ExportSource::block)).toList();
    this.version = version;
    this.commit = commit;
  }

  /**
   * Builds every export waiting for one tenant.
   *
   * <p>Called inside that tenant's context.
   *
   * @param tenantId whose exports
   * @return how many archives were built
   */
  public int runFor(UUID tenantId) {
    int built = 0;
    for (Optional<UUID> claimed = jobs.claimNext(tenantId);
        claimed.isPresent();
        claimed = jobs.claimNext(tenantId)) {
      build(tenantId, claimed.get());
      built++;
    }
    return built;
  }

  /**
   * Builds one archive and records what became of it.
   *
   * @param tenantId whose
   * @param jobId which job
   */
  private void build(UUID tenantId, UUID jobId) {
    Path temporary = null;
    try {
      temporary = Files.createTempFile("homeinv-export-", ".zip");
      ArchiveWriter.Archive archive;
      try (ArchiveWriter writer = new ArchiveWriter(json, temporary)) {
        writer.manifest("format", 1);
        writer.manifest("tenantId", tenantId.toString());
        writer.manifest("exportedAt", Instant.now(clock).toString());
        writer.manifest("producedBy", java.util.Map.of("version", version, "commit", commit));

        // Built as the person who asked for it. Not to authorise the export --
        // that was decided when they asked -- but because opening a sealed field
        // depends on which role they hold and on how recently they proved a
        // second factor (REQ-AUTH-011), and the worker has no caller of its own.
        // Reconstructed from what was recorded then, so a role granted since does
        // not widen an archive somebody asked for before they had it.
        CallerContext.runAs(
            callerOf(tenantId, jobId), () -> writeEveryBlock(tenantId, jobId, writer));
        archive = writer.finish();
      }

      try (InputStream bytes = Files.newInputStream(archive.file())) {
        blobs.store(tenantId, archive.sha256(), bytes);
      }
      jobs.ready(tenantId, jobId, archive.sha256(), archive.byteSize());
      log.info("An export archive of {} byte(s) was built for tenant {}", archive.byteSize(), tenantId);
    } catch (IOException | RuntimeException failed) {
      // Recorded rather than rethrown: one tenant's export failing must not stop
      // the run, and the message is shown to whoever asked -- so it says what
      // happened and never carries a stack trace.
      log.error("An export failed for tenant {}", tenantId, failed);
      jobs.failed(tenantId, jobId, reasonOf(failed));
    } finally {
      deleteQuietly(temporary);
    }
  }

  /**
   * Asks every block for its share, in a fixed order, reporting progress as it goes.
   *
   * @param tenantId whose export
   * @param jobId which job, so progress lands on the right row
   * @param writer the archive being built
   */
  private void writeEveryBlock(UUID tenantId, UUID jobId, ArchiveWriter writer) {
    int done = 0;
    for (ExportSource source : sources) {
      writer.beginBlock(source.block());
      source.exportTo(writer);
      done++;
      // Coarse and honest: a percentage of the blocks finished. It is read by
      // somebody deciding whether to keep waiting, and "5 of 9 blocks" is
      // what they actually want to know.
      jobs.progress(tenantId, jobId, done * 95 / Math.max(1, sources.size()));
    }
  }

  /**
   * The caller the archive is built as.
   *
   * <p>Never null: a job requested with no caller — a schedule, a test — still has to run, and it
   * runs as somebody who holds no role and has proved no second factor. Everything sensitive is
   * then withheld and the manifest says so, which is the direction this must fail in.
   *
   * @param tenantId whose export
   * @param jobId which job
   * @return who to build it as
   */
  private CallerContext.Caller callerOf(UUID tenantId, UUID jobId) {
    ExportJobQueries.Requester requester =
        jobs.requesterOf(tenantId, jobId)
            .orElse(new ExportJobQueries.Requester(null, null, null, null));
    return new CallerContext.Caller(
        requester.userId(),
        tenantId,
        requester.role(),
        requester.roleDefinitionId(),
        null,
        requester.secondFactorAt());
  }

  /**
   * What to tell the person who asked.
   *
   * @param failure what went wrong
   * @return one sentence, never a stack trace and never a credential
   */
  private static String reasonOf(Throwable failure) {
    String detail = failure.getMessage();
    return detail == null || detail.isBlank()
        ? "The export could not be built (" + failure.getClass().getSimpleName() + ")."
        : "The export could not be built: " + detail;
  }

  private static void deleteQuietly(Path file) {
    if (file == null) {
      return;
    }
    try {
      Files.deleteIfExists(file);
    } catch (IOException leftBehind) {
      // A temporary file that outlives its build is untidy and harmless; the
      // operating system reclaims the directory. Not worth failing a finished
      // export over.
      log.debug("A temporary export file could not be removed: {}", file, leftBehind);
    }
  }

  /**
   * The tenant context a build needs, for the caller that spans tenants.
   *
   * @param tenantId whose exports
   * @return how many archives were built
   */
  public int runAsTenant(UUID tenantId) {
    return TenantContext.callAs(tenantId, () -> runFor(tenantId));
  }
}
