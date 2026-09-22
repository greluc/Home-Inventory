/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.portability.infrastructure;

import de.greluc.homeinv.portability.api.ExportService.ExportJobView;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Export jobs, in SQL (REQ-PORT-005). */
@Component
@RequiredArgsConstructor
public class ExportJobQueries {

  /** Written out rather than assembled, because a joined SQL literal is refused (REQ-SEC-031). */
  private static final String JOBS =
      """
      select id, state, progress, byte_size, failure, requested_at, requested_by, finished_at
      from portability.export_job
      where tenant_id = ?
      order by requested_at desc, id desc
      limit ?
      """;

  private static final String ONE_JOB =
      """
      select id, state, progress, byte_size, failure, requested_at, requested_by, finished_at
      from portability.export_job
      where tenant_id = ? and id = ?
      """;

  private final JdbcClient jdbc;

  /**
   * Which tenants have an export waiting.
   *
   * <p>Through the {@code SECURITY DEFINER} function of {@code V63}: the runner has no tenant
   * context because it is looking for the ones that need one (07 §7.5).
   *
   * @return the tenants
   */
  @Transactional(readOnly = true)
  public List<UUID> tenantsWithQueuedExports() {
    return jdbc
        .sql("select tenant_id from portability.tenants_with_queued_exports()")
        .query((ResultSet rs, int row) -> rs.getObject(1, UUID.class))
        .list();
  }

  /**
   * Records a request, together with the authority it was made under.
   *
   * <p>The role and the second-factor moment are copied onto the job because the caller is gone by
   * the time the worker builds the archive, and what may be opened out of a sealed field depends on
   * them (REQ-AUTH-011). A membership changed afterwards does not change the archive: it is of the
   * moment it was asked for.
   *
   * @param tenantId whose
   * @param actor who asked
   * @param role their built-in role, or null when there is no caller
   * @param roleDefinitionId the tenant-owned role extending it, or null
   * @param secondFactorAt when they last proved a second factor, or null
   * @return the new job's id
   */
  @Transactional
  public UUID queue(
      UUID tenantId, UUID actor, String role, UUID roleDefinitionId, Instant secondFactorAt) {
    return jdbc
        .sql(
            """
            insert into portability.export_job
                (tenant_id, requested_by, caller_role, caller_role_definition_id,
                 caller_second_factor_at)
            values (?, ?, ?, ?, ?)
            returning id
            """)
        .param(tenantId)
        .param(actor)
        .param(role)
        .param(roleDefinitionId)
        .param(secondFactorAt == null ? null : java.sql.Timestamp.from(secondFactorAt))
        .query(UUID.class)
        .single();
  }

  /**
   * The authority one job was requested under.
   *
   * @param tenantId whose
   * @param jobId which job
   * @return who asked and what they could read, or empty when the job is not this tenant's
   */
  @Transactional(readOnly = true)
  public Optional<Requester> requesterOf(UUID tenantId, UUID jobId) {
    return jdbc
        .sql(
            """
            select requested_by, caller_role, caller_role_definition_id, caller_second_factor_at
            from portability.export_job
            where tenant_id = ? and id = ?
            """)
        .param(tenantId)
        .param(jobId)
        .query(
            (rs, rowNum) -> {
              java.sql.Timestamp proved = rs.getTimestamp("caller_second_factor_at");
              return new Requester(
                  rs.getObject("requested_by", UUID.class),
                  rs.getString("caller_role"),
                  rs.getObject("caller_role_definition_id", UUID.class),
                  proved == null ? null : proved.toInstant());
            })
        .optional();
  }

  /**
   * Who asked for an archive, and under what authority.
   *
   * @param userId the person, or null for a job requested with no caller
   * @param role their built-in role at request time, or null
   * @param roleDefinitionId the tenant-owned role extending it, or null
   * @param secondFactorAt when they last proved a second factor, or null — which means a sensitive
   *     field stays sealed (REQ-AUTH-011)
   */
  public record Requester(
      UUID userId, String role, UUID roleDefinitionId, Instant secondFactorAt) {}

  /**
   * The oldest job still waiting, claimed for this run.
   *
   * <p>{@code for update skip locked}, so two workers never build the same archive: the second one
   * passes over a row the first is holding rather than waiting for it. Claiming and running are one
   * transaction away from each other on purpose — the build takes minutes and a transaction held
   * open for that would hold a row lock for it too.
   *
   * @param tenantId whose
   * @return the claimed job, or empty when there is nothing to do
   */
  @Transactional
  public Optional<UUID> claimNext(UUID tenantId) {
    Optional<UUID> claimed =
        jdbc
            .sql(
                """
                select id from portability.export_job
                 where tenant_id = ? and state = 'QUEUED'
                 order by requested_at
                 limit 1
                 for update skip locked
                """)
            .param(tenantId)
            .query(UUID.class)
            .optional();
    claimed.ifPresent(
        id ->
            jdbc.sql(
                    """
                    update portability.export_job
                       set state = 'RUNNING', progress = 0
                     where tenant_id = ? and id = ? and state = 'QUEUED'
                    """)
                .param(tenantId)
                .param(id)
                .update());
    return claimed;
  }

  /**
   * Notes how far along a job is.
   *
   * @param tenantId whose
   * @param id which job
   * @param percent how far, 0 to 100
   */
  @Transactional
  public void progress(UUID tenantId, UUID id, int percent) {
    jdbc.sql(
            """
            update portability.export_job
               set progress = ?
             where tenant_id = ? and id = ? and state = 'RUNNING'
            """)
        .param(Math.clamp(percent, 0, 100))
        .param(tenantId)
        .param(id)
        .update();
  }

  /**
   * Records that the archive is there.
   *
   * @param tenantId whose
   * @param id which job
   * @param sha256 the archive's content address
   * @param byteSize how large it is
   */
  @Transactional
  public void ready(UUID tenantId, UUID id, String sha256, long byteSize) {
    jdbc.sql(
            """
            update portability.export_job
               set state = 'READY', progress = 100, sha256 = ?, byte_size = ?, finished_at = now()
             where tenant_id = ? and id = ?
            """)
        .param(sha256)
        .param(byteSize)
        .param(tenantId)
        .param(id)
        .update();
  }

  /**
   * Records that it did not work, in words shown to whoever asked.
   *
   * @param tenantId whose
   * @param id which job
   * @param failure what went wrong; never a stack trace, never a credential
   */
  @Transactional
  public void failed(UUID tenantId, UUID id, String failure) {
    jdbc.sql(
            """
            update portability.export_job
               set state = 'FAILED', failure = ?, finished_at = now()
             where tenant_id = ? and id = ?
            """)
        .param(failure.length() > 2000 ? failure.substring(0, 2000) : failure)
        .param(tenantId)
        .param(id)
        .update();
  }

  /**
   * One job.
   *
   * @param tenantId whose
   * @param id which one
   * @return it, or empty
   */
  @Transactional(readOnly = true)
  public Optional<ExportJobView> byId(UUID tenantId, UUID id) {
    return jdbc.sql(ONE_JOB).param(tenantId).param(id).query(ExportJobQueries::toView).optional();
  }

  /**
   * The tenant's jobs, newest first.
   *
   * @param tenantId whose
   * @param limit how many at most
   * @return the jobs
   */
  @Transactional(readOnly = true)
  public List<ExportJobView> all(UUID tenantId, int limit) {
    return jdbc.sql(JOBS).param(tenantId).param(limit).query(ExportJobQueries::toView).list();
  }

  /**
   * The archive's content address, for a job that has one.
   *
   * @param tenantId whose
   * @param id which job
   * @return the digest, or empty when the job has no archive
   */
  @Transactional(readOnly = true)
  public Optional<String> archiveOf(UUID tenantId, UUID id) {
    return jdbc
        .sql("select sha256 from portability.export_job where tenant_id = ? and id = ?")
        .param(tenantId)
        .param(id)
        .query(String.class)
        .optional();
  }

  private static ExportJobView toView(ResultSet rs, int rowNum) throws SQLException {
    java.sql.Timestamp finished = rs.getTimestamp("finished_at");
    long size = rs.getLong("byte_size");
    // Read IMMEDIATELY after the column it is about: `wasNull` reports on the
    // last value read from the row, and reading `progress` in between made it
    // answer about that instead -- so an unfinished job reported a size of 0
    // rather than none.
    Long byteSize = rs.wasNull() ? null : size;
    return new ExportJobView(
        rs.getObject("id", UUID.class),
        rs.getString("state"),
        rs.getInt("progress"),
        byteSize,
        rs.getString("failure"),
        rs.getTimestamp("requested_at").toInstant(),
        rs.getObject("requested_by", UUID.class),
        finished == null ? null : finished.toInstant());
  }
}
