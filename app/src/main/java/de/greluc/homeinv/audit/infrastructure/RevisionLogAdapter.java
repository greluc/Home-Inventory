/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.audit.infrastructure;

import de.greluc.homeinv.platform.Page;
import de.greluc.homeinv.audit.api.RevisionLog;
import de.greluc.homeinv.platform.CursorCodec;
import de.greluc.homeinv.platform.NotFoundException;
import de.greluc.homeinv.platform.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes and reads {@code audit.revision_record}.
 *
 * <p>{@link Propagation#MANDATORY} on the write: a revision must be part of the transaction that
 * made the change it records. A history that could be written separately is a history with gaps
 * wherever the second write failed — and a gap reads as "nothing happened", which is the one thing
 * it must never say.
 *
 * <p>The role has {@code INSERT} and {@code SELECT} on this table and nothing else, so an update or
 * a delete is refused by the database rather than by a rule somebody has to remember.
 */
@Component
@RequiredArgsConstructor
public class RevisionLogAdapter implements RevisionLog {

  /** The cap REQ-NFR-010 puts on every page of every collection. */
  private static final int MAX_PAGE = 200;

  /** What a history cursor is bound to; the entity is in the query, not in the cursor. */
  private static final String CURSOR = "revisions";

  private static final String COLUMNS =
      "select revision, change_kind, snapshot::text as snapshot, changed_at, changed_by"
          + " from audit.revision_record";
  private static final String PAGE =
      COLUMNS
          + " where tenant_id = ? and entity_type = ? and entity_id = ?"
          + " order by revision desc limit ?";
  private static final String PAGE_AFTER =
      COLUMNS
          + " where tenant_id = ? and entity_type = ? and entity_id = ? and revision < ?"
          + " order by revision desc limit ?";

  private final JdbcClient jdbc;
  private final CursorCodec cursors;

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public long record(
      EntityType entityType, UUID entityId, ChangeKind kind, String snapshot, UUID actor) {
    UUID tenantId = TenantContext.require();
    // The number is read and written in one statement, so two concurrent writers
    // cannot both read the same maximum. They would collide on the unique key
    // rather than silently sharing a number — and concurrent writes to one entity
    // are already serialised by its optimistic lock, so this is the belt to that
    // brace.
    Long assigned =
        jdbc.sql(
                """
                insert into audit.revision_record
                    (id, tenant_id, entity_type, entity_id, revision, change_kind, snapshot,
                     changed_by)
                select ?, ?, ?, ?, coalesce(max(revision), 0) + 1, ?, ?::jsonb, ?
                from audit.revision_record
                where tenant_id = ? and entity_type = ? and entity_id = ?
                returning revision
                """)
            .params(
                UUID.randomUUID(),
                tenantId,
                entityType.name(),
                entityId,
                kind.name(),
                snapshot == null || snapshot.isBlank() ? "{}" : snapshot,
                actor,
                tenantId,
                entityType.name(),
                entityId)
            .query(Long.class)
            .single();
    return assigned;
  }

  @Override
  @Transactional(readOnly = true)
  public Page<RevisionLog.RevisionView> history(EntityType entityType, UUID entityId, String cursor, int limit) {
    UUID tenantId = TenantContext.require();
    int size = Math.clamp(limit, 1, MAX_PAGE);

    List<RevisionView> rows;
    if (cursor == null || cursor.isBlank()) {
      rows =
          jdbc.sql(PAGE)
              .params(tenantId, entityType.name(), entityId, size)
              .query(this::revisionOf)
              .list();
    } else {
      // The position is a revision number, which is already an ordered key of its
      // own — the cursor still carries the pair, so a tampered one is refused by
      // the same code path as everywhere else (REQ-SEC-106).
      CursorCodec.Position from = cursors.decode(cursor, CURSOR);
      rows =
          jdbc.sql(PAGE_AFTER)
              .params(tenantId, entityType.name(), entityId, from.createdAt().toEpochMilli(), size)
              .query(this::revisionOf)
              .list();
    }

    String next =
        rows.size() == size
            ? cursors.encode(
                new CursorCodec.Position(
                    java.time.Instant.ofEpochMilli(rows.getLast().revision()), entityId),
                CURSOR)
            : null;
    return Page.of(rows, next);
  }

  @Override
  @Transactional(readOnly = true)
  public RevisionView revision(EntityType entityType, UUID entityId, long revision) {
    return jdbc
        .sql(COLUMNS + " where tenant_id = ? and entity_type = ? and entity_id = ? and revision = ?")
        .params(TenantContext.require(), entityType.name(), entityId, revision)
        .query(this::revisionOf)
        .optional()
        .orElseThrow(() -> new NotFoundException("revision", entityId));
  }

  /**
   * Maps one row.
   *
   * @param rs the row
   * @param rowNum which row, as the mapper contract takes it
   * @return the revision
   * @throws SQLException when the row cannot be read
   */
  private RevisionView revisionOf(ResultSet rs, int rowNum) throws SQLException {
    return new RevisionView(
        rs.getLong("revision"),
        ChangeKind.valueOf(rs.getString("change_kind")),
        rs.getString("snapshot"),
        rs.getTimestamp("changed_at").toInstant(),
        rs.getObject("changed_by", UUID.class));
  }
}
