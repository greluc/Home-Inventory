/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.infrastructure;

import de.greluc.homeinv.inventory.api.ItemRelations;
import de.greluc.homeinv.platform.CursorCodec;
import de.greluc.homeinv.platform.LastRow;
import de.greluc.homeinv.platform.NotFoundException;
import de.greluc.homeinv.platform.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Relations between items, over one directed table (REQ-CORE-006).
 *
 * <p>The read is a union of the two directions with a flag saying which end was asked. That is what
 * lets one row answer both "what are this camera's accessories" and "what is this lens an accessory
 * of" without storing the relation twice — and two rows for one fact are two rows that can disagree.
 */
@Component
@RequiredArgsConstructor
public class ItemRelationAdapter implements ItemRelations {

  /** The cap REQ-NFR-010 puts on every page of every collection. */
  private static final int MAX_PAGE = 200;

  /** What a relation cursor is bound to. */
  private static final String CURSOR = "item-relations";

  /**
   * Both directions in one answer.
   *
   * <p>{@code inbound} is what the two halves differ by: the first reads the rows this item states,
   * the second the rows that point at it. Ordered by creation and id, which is the pair the keyset
   * cursor resumes from.
   */
  private static final String RELATIONS =
      """
      select id, source_id, target_id, relation_type, false as inbound, created_at
      from inventory.item_relation where tenant_id = ? and source_id = ?
      union all
      select id, source_id, target_id, relation_type, true as inbound, created_at
      from inventory.item_relation where tenant_id = ? and target_id = ?
      order by created_at, id
      limit ?
      """;

  private static final String RELATIONS_AFTER =
      """
      select * from (
        select id, source_id, target_id, relation_type, false as inbound, created_at
        from inventory.item_relation where tenant_id = ? and source_id = ?
        union all
        select id, source_id, target_id, relation_type, true as inbound, created_at
        from inventory.item_relation where tenant_id = ? and target_id = ?
      -- `either_end` and not `both`: `both` is a reserved word in PostgreSQL
      -- (`trim(both ...)`), so the alias was a syntax error -- in the statement
      -- that only the SECOND page reaches, which is why it stood for months.
      ) either_end
      where created_at > ? or (created_at = ? and id > ?)
      order by created_at, id
      limit ?
      """;

  private final JdbcClient jdbc;
  private final CursorCodec cursors;
  private final ItemRepository items;

  // Where the last row of a page sat lives in a `LastRow` per call: a field here
  // would be shared by every request in flight, this being a singleton.

  @Override
  @Transactional
  public RelationView relate(UUID sourceId, UUID targetId, RelationType type, UUID actor) {
    UUID tenantId = TenantContext.require();
    if (sourceId.equals(targetId)) {
      throw new IllegalArgumentException(
          "An item cannot be related to itself: a thing is not an accessory of itself, nor a part "
              + "of itself.");
    }
    // Both ends are checked here rather than left to the foreign keys, so a
    // caller naming something this tenant cannot see gets a 404 instead of a
    // constraint violation surfacing as a 500.
    items.findLive(tenantId, sourceId).orElseThrow(() -> new NotFoundException("item", sourceId));
    items.findLive(tenantId, targetId).orElseThrow(() -> new NotFoundException("item", targetId));

    jdbc.sql(
            """
            insert into inventory.item_relation
                (id, tenant_id, source_id, target_id, relation_type, created_by)
            values (?, ?, ?, ?, ?, ?)
            on conflict do nothing
            """)
        .params(UUID.randomUUID(), tenantId, sourceId, targetId, type.name(), actor)
        .update();

    return jdbc
        .sql(
            """
            select id, source_id, target_id, relation_type, false as inbound, created_at
            from inventory.item_relation
            where tenant_id = ? and source_id = ? and target_id = ? and relation_type = ?
            """)
        .params(tenantId, sourceId, targetId, type.name())
        // A single row: the holder is a formality, because nothing pages one.
        .query((rs, rowNum) -> relationOf(rs, new LastRow()))
        .single();
  }

  @Override
  @Transactional
  public void unrelate(UUID relationId, UUID actor) {
    jdbc.sql("delete from inventory.item_relation where tenant_id = ? and id = ?")
        .params(TenantContext.require(), relationId)
        .update();
  }

  @Override
  @Transactional(readOnly = true)
  public RelationPage relationsOf(UUID itemId, String cursor, int limit) {
    UUID tenantId = TenantContext.require();
    int size = Math.clamp(limit, 1, MAX_PAGE);
    items.findAny(tenantId, itemId).orElseThrow(() -> new NotFoundException("item", itemId));
    LastRow last = new LastRow();

    List<RelationView> rows;
    if (cursor == null || cursor.isBlank()) {
      rows =
          jdbc.sql(RELATIONS)
              .params(tenantId, itemId, tenantId, itemId, size)
              .query((rs, rowNum) -> relationOf(rs, last))
              .list();
    } else {
      CursorCodec.Position from = cursors.decode(cursor, CURSOR);
      // Wrapped, not an Instant: the driver cannot infer a SQL type for one, and
      // the statement is only reached on the second page.
      java.sql.Timestamp at = java.sql.Timestamp.from(from.createdAt());
      rows =
          jdbc.sql(RELATIONS_AFTER)
              .params(tenantId, itemId, tenantId, itemId, at, at, from.id(), size)
              .query((rs, rowNum) -> relationOf(rs, last))
              .list();
    }
    String next =
        rows.size() == size && last.position() != null
            ? cursors.encode(last.position(), CURSOR)
            : null;
    return new RelationPage(rows, next);
  }

  /**
   * Maps one row.
   *
   * @param rs the row
   * @param last where to record this row's position, for the next page's cursor
   * @return the relation
   * @throws SQLException when the row cannot be read
   */
  private RelationView relationOf(ResultSet rs, LastRow last) throws SQLException {
    last.at(rs.getTimestamp("created_at").toInstant(), rs.getObject("id", UUID.class));
    return new RelationView(
        rs.getObject("id", UUID.class),
        rs.getObject("source_id", UUID.class),
        rs.getObject("target_id", UUID.class),
        RelationType.valueOf(rs.getString("relation_type")),
        rs.getBoolean("inbound"));
  }
}
