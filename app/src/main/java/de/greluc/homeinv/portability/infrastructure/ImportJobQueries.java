/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.portability.infrastructure;

import de.greluc.homeinv.portability.api.ImportService.ImportJobView;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The SQL of {@code portability.import_job}.
 *
 * <h2>Every write here is its own transaction</h2>
 *
 * <p>Deliberately, and it is the one place in the import that is. REQ-PORT-007 puts the whole
 * import in one transaction so that a failure leaves nothing behind — but the job row has to
 * survive that rollback, or a failed import would erase the record of having failed and the person
 * who asked would see a job stuck in {@code RUNNING} for ever. {@link Propagation#REQUIRES_NEW}
 * therefore suspends the import's transaction for the moment it takes to record an outcome.
 */
@Component
@RequiredArgsConstructor
public class ImportJobQueries {

  private final JdbcClient jdbc;

  /**
   * Which tenants have an import waiting.
   *
   * <p>Through the {@code SECURITY DEFINER} function of {@code V65}: the runner has no tenant
   * context because it is looking for the ones that need one (07 §7.5).
   *
   * @return the tenants
   */
  @Transactional(readOnly = true)
  public List<UUID> tenantsWithQueuedImports() {
    return jdbc
        .sql("select tenant_id from portability.tenants_with_queued_imports()")
        .query((ResultSet rs, int row) -> rs.getObject(1, UUID.class))
        .list();
  }

  /**
   * Records an uploaded archive waiting to be read.
   *
   * @param tenantId whose
   * @param actor who asked
   * @param sha256 where the archive is in the blob store
   * @param byteSize how large it is
   * @param dryRun whether the work is thrown away at the end
   * @return the new job's id
   */
  @Transactional
  public UUID queue(UUID tenantId, UUID actor, String sha256, long byteSize, boolean dryRun) {
    return jdbc
        .sql(
            """
            insert into portability.import_job
                (tenant_id, requested_by, sha256, byte_size, dry_run)
            values (?, ?, ?, ?, ?)
            returning id
            """)
        .param(tenantId)
        .param(actor)
        .param(sha256)
        .param(byteSize)
        .param(dryRun)
        .query(UUID.class)
        .single();
  }

  /**
   * The oldest job still waiting, claimed for this run.
   *
   * @param tenantId whose
   * @return the job, or empty when there is none
   */
  @Transactional
  public Optional<Claimed> claimNext(UUID tenantId) {
    Optional<Claimed> claimed =
        jdbc
            .sql(
                """
                select id, sha256, dry_run from portability.import_job
                 where tenant_id = ? and state = 'QUEUED'
                 order by requested_at
                 limit 1
                 for update skip locked
                """)
            .param(tenantId)
            .query(
                (rs, rowNum) ->
                    new Claimed(
                        rs.getObject("id", UUID.class),
                        rs.getString("sha256"),
                        rs.getBoolean("dry_run")))
            .optional();
    claimed.ifPresent(
        job ->
            jdbc.sql(
                    """
                    update portability.import_job
                       set state = 'RUNNING', progress = 0
                     where tenant_id = ? and id = ? and state = 'QUEUED'
                    """)
                .param(tenantId)
                .param(job.id())
                .update());
    return claimed;
  }

  /**
   * How far along one job is.
   *
   * @param tenantId whose
   * @param jobId which job
   * @param percent 0 to 100
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void progress(UUID tenantId, UUID jobId, int percent) {
    jdbc.sql(
            """
            update portability.import_job set progress = ?
             where tenant_id = ? and id = ? and state = 'RUNNING'
            """)
        .param(Math.clamp(percent, 0, 100))
        .param(tenantId)
        .param(jobId)
        .update();
  }

  /**
   * Records that an import finished, with what it did.
   *
   * @param tenantId whose
   * @param jobId which job
   * @param reportJson the report, as a JSON object
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void done(UUID tenantId, UUID jobId, String reportJson) {
    jdbc.sql(
            """
            update portability.import_job
               set state = 'DONE', progress = 100, report = ?::jsonb, finished_at = now()
             where tenant_id = ? and id = ?
            """)
        .param(reportJson)
        .param(tenantId)
        .param(jobId)
        .update();
  }

  /**
   * Records that an import failed, and why.
   *
   * @param tenantId whose
   * @param jobId which job
   * @param reason one sentence, never a stack trace and never a credential
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void failed(UUID tenantId, UUID jobId, String reason) {
    jdbc.sql(
            """
            update portability.import_job
               set state = 'FAILED', failure = ?, finished_at = now()
             where tenant_id = ? and id = ?
            """)
        .param(reason)
        .param(tenantId)
        .param(jobId)
        .update();
  }

  /**
   * One job, as the API shows it.
   *
   * @param tenantId whose
   * @param id which job
   * @return the job, or empty when it is not this tenant's
   */
  @Transactional(readOnly = true)
  public Optional<ImportJobView> byId(UUID tenantId, UUID id) {
    return jdbc
        .sql(
            """
            select id, state, dry_run, progress, byte_size, report::text as report, failure,
                   requested_at, finished_at
            from portability.import_job
            where tenant_id = ? and id = ?
            """)
        .param(tenantId)
        .param(id)
        .query(ImportJobQueries::toView)
        .optional();
  }

  /**
   * This tenant's import jobs, newest first.
   *
   * @param tenantId whose
   * @param limit how many at most
   * @return the jobs
   */
  @Transactional(readOnly = true)
  public List<ImportJobView> all(UUID tenantId, int limit) {
    return jdbc
        .sql(
            """
            select id, state, dry_run, progress, byte_size, report::text as report, failure,
                   requested_at, finished_at
            from portability.import_job
            where tenant_id = ?
            order by requested_at desc
            limit ?
            """)
        .param(tenantId)
        .param(limit)
        .query(ImportJobQueries::toView)
        .list();
  }

  /**
   * One row as the API's view of it.
   *
   * @param rs the row
   * @param rowNum which row
   * @return the view
   * @throws SQLException when it cannot be read
   */
  private static ImportJobView toView(ResultSet rs, int rowNum) throws SQLException {
    java.sql.Timestamp finished = rs.getTimestamp("finished_at");
    return new ImportJobView(
        rs.getObject("id", UUID.class),
        rs.getString("state"),
        rs.getBoolean("dry_run"),
        rs.getInt("progress"),
        rs.getLong("byte_size"),
        rs.getString("report"),
        rs.getString("failure"),
        rs.getTimestamp("requested_at").toInstant(),
        finished == null ? null : finished.toInstant());
  }

  /**
   * A job claimed for this run.
   *
   * @param id which job
   * @param sha256 where its archive is
   * @param dryRun whether to throw the work away at the end
   */
  public record Claimed(UUID id, String sha256, boolean dryRun) {}
}
