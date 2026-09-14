/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tenancy.infrastructure;

import de.greluc.homeinv.platform.Page;
import de.greluc.homeinv.platform.CursorCodec;
import de.greluc.homeinv.platform.TenantErasure;
import de.greluc.homeinv.tenancy.api.ErasureCertificates;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads what an erasure certificate has to say, and writes it (REQ-TEN-011).
 *
 * <p>{@code tenancy.erasure_certificate} is instance-wide (07 §7.1) for the plainest reason on that
 * list: a tenant-scoped certificate would be removed by the very run that writes it. Evidence of an
 * erasure has to outlive the thing it is about.
 *
 * <p>It is written once and never changed: the table grants {@code INSERT} and {@code SELECT} and
 * nothing else, which is the same argument REQ-SEC-069 makes about the audit log — a record that
 * could be edited afterwards would be evidence of nothing.
 */
@Component
@RequiredArgsConstructor
public class ErasureCertificateWriter implements ErasureCertificates {

  /** The cap REQ-NFR-010 puts on every page of every collection. */
  private static final int MAX_PAGE = 200;

  /** What a certificate cursor is bound to. */
  private static final String CURSOR = "erasure-certificates";

  /**
   * One certificate.
   *
   * <p>The columns are repeated in each of the three statements rather than joined in from a
   * constant. The rule that keeps SQL out of string concatenation admits no exception for a
   * constant (REQ-SEC-031), and three literals a reader can check beat one they have to assemble.
   */
  private static final String ONE =
      "select erased_tenant_id, tenant_name, requested_at, requested_by, completed_at,"
          + " report::text as report"
          + " from tenancy.erasure_certificate"
          + " where erased_tenant_id = ?";

  /**
   * Newest first, and {@code erased_tenant_id} breaks the tie.
   *
   * <p>Not the primary key: the cursor carries an instant and an id, and the id a reader of this
   * page has is the tenant's. It orders as well as any other, because the unique index makes it
   * unique per certificate.
   */
  private static final String PAGE =
      "select erased_tenant_id, tenant_name, requested_at, requested_by, completed_at,"
          + " report::text as report"
          + " from tenancy.erasure_certificate"
          + " order by completed_at desc, erased_tenant_id desc limit ?";

  private static final String PAGE_AFTER =
      "select erased_tenant_id, tenant_name, requested_at, requested_by, completed_at,"
          + " report::text as report"
          + " from tenancy.erasure_certificate"
          + " where completed_at < ? or (completed_at = ? and erased_tenant_id < ?)"
          + " order by completed_at desc, erased_tenant_id desc limit ?";

  /**
   * What the certificate needs from the tenant, read before anything is removed.
   *
   * <p>Under the tenant's own context and its own policy: the name is one of the things being
   * erased, so it has to be taken while it is still there.
   */
  private static final String SNAPSHOT =
      "select name, deletion_requested_at, deletion_requested_by from tenancy.tenant";

  private static final String INSERT =
      "insert into tenancy.erasure_certificate"
          + " (erased_tenant_id, tenant_name, requested_at, requested_by, report)"
          + " values (?, ?, ?, ?, ?::jsonb) on conflict (erased_tenant_id) do nothing";

  private final JdbcClient jdbc;
  private final CursorCodec cursors;
  private final ObjectMapper json;

  /**
   * What the tenant was called and who asked for it to go.
   *
   * @param name the tenant's display name at the time
   * @param requestedAt when the erasure was asked for
   * @param requestedBy which account asked
   */
  public record Snapshot(String name, Instant requestedAt, UUID requestedBy) {}

  /**
   * Reads the tenant's own row, inside its context, before the erasure starts.
   *
   * @param tenantId the tenant, for the error when there is no such row
   * @return what the certificate will say about it
   */
  @Transactional(readOnly = true)
  public Snapshot snapshotOf(UUID tenantId) {
    return jdbc
        .sql(SNAPSHOT)
        .query(
            (rs, rowNum) ->
                new Snapshot(
                    rs.getString("name"),
                    rs.getObject("deletion_requested_at", java.time.OffsetDateTime.class)
                        .toInstant(),
                    rs.getObject("deletion_requested_by", UUID.class)))
        .optional()
        .orElseThrow(
            () -> new de.greluc.homeinv.platform.NotFoundException("tenant", tenantId));
  }

  /**
   * Writes the certificate.
   *
   * <p>Outside any tenant context, because the table has none: it is instance-wide. Idempotent on
   * the tenant, so a run resumed after a failure does not issue a second certificate for the same
   * erasure — the first one is the record, and two would be two accounts of one event.
   *
   * @param tenantId the tenant that was erased
   * @param snapshot what it was called and who asked
   * @param report one entry per building block
   */
  @Transactional
  public void write(UUID tenantId, Snapshot snapshot, List<TenantErasure.BlockReport> report) {
    jdbc.sql(INSERT)
        .params(
            tenantId,
            snapshot.name(),
            java.sql.Timestamp.from(snapshot.requestedAt()),
            snapshot.requestedBy(),
            json.writeValueAsString(report))
        .update();
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Certificate> forTenant(UUID tenantId) {
    return jdbc.sql(ONE).param(tenantId).query(this::certificateOf).optional();
  }

  @Override
  @Transactional(readOnly = true)
  public Page<ErasureCertificates.Certificate> certificates(String cursor, int limit) {
    int size = Math.clamp(limit, 1, MAX_PAGE);
    List<Certificate> rows;
    if (cursor == null || cursor.isBlank()) {
      rows = jdbc.sql(PAGE).param(size).query(this::certificateOf).list();
    } else {
      CursorCodec.Position from = cursors.decode(cursor, CURSOR);
      rows =
          jdbc.sql(PAGE_AFTER)
              .params(
                  java.sql.Timestamp.from(from.createdAt()),
                  java.sql.Timestamp.from(from.createdAt()),
                  from.id(),
                  size)
              .query(this::certificateOf)
              .list();
    }

    // From the last row rather than from a field on this component. A mapper that
    // wrote where it had got to would be shared state on a singleton, and two
    // operators paging at once would hand each other their cursors.
    String next =
        rows.size() == size
            ? cursors.encode(
                new CursorCodec.Position(rows.getLast().completedAt(), rows.getLast().tenantId()),
                CURSOR)
            : null;
    return Page.of(rows, next);
  }

  /**
   * Maps one stored certificate.
   *
   * @param rs the row
   * @param rowNum which row, as the mapper contract takes it
   * @return the certificate
   * @throws java.sql.SQLException when the row cannot be read
   */
  private Certificate certificateOf(java.sql.ResultSet rs, int rowNum)
      throws java.sql.SQLException {
    return new Certificate(
        rs.getObject("erased_tenant_id", UUID.class),
        rs.getString("tenant_name"),
        rs.getObject("requested_at", java.time.OffsetDateTime.class).toInstant(),
        rs.getObject("requested_by", UUID.class),
        rs.getObject("completed_at", java.time.OffsetDateTime.class).toInstant(),
        json.readValue(
            rs.getString("report"),
            json.getTypeFactory()
                .constructCollectionType(List.class, TenantErasure.BlockReport.class)));
  }
}
