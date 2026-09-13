/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tagging.infrastructure;

import de.greluc.homeinv.platform.CursorCodec;
import de.greluc.homeinv.platform.NotFoundException;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.tagging.api.TagAssigned;
import de.greluc.homeinv.tagging.api.TagCreated;
import de.greluc.homeinv.tagging.api.TagGroupView;
import de.greluc.homeinv.tagging.api.TagMerged;
import de.greluc.homeinv.tagging.api.TagService;
import de.greluc.homeinv.tagging.api.TagUnassigned;
import de.greluc.homeinv.tagging.api.TagView;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * The tag block, over three flat tables.
 *
 * <p>{@link JdbcClient} rather than JPA aggregates, for the reason the catalog adapters give: a tag
 * is a name, a colour and a group, an assignment is a pair, and an aggregate per row would be
 * structure written for nobody. The one operation with real behaviour — the merge — is three
 * statements that must happen together, which a transaction expresses better than an aggregate
 * would.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TagAdapter implements TagService {

  /** The cap REQ-NFR-010 puts on every page of every collection. */
  private static final int MAX_PAGE = 200;

  private static final String TAG_CURSOR = "tags";
  private static final String ASSIGNED_CURSOR = "tags-of";

  // Ordered by the tag's own creation, not by its name, because that is what the
  // keyset cursor can resume from: a name is not unique enough to page on once
  // two tenants' habits collide, and the client sorts a handful of names itself.
  private static final String TAGS_OF_ITEM =
      "select t.id, t.name, t.tag_group_id, t.colour, t.icon, t.merged_into, t.created_at"
          + " from tagging.tag t"
          + " join tagging.tag_assignment a on a.tenant_id = t.tenant_id and a.tag_id = t.id"
          + " where t.tenant_id = ? and a.item_id = ?"
          + " order by t.created_at, t.id limit ?";
  private static final String TAGS_OF_ITEM_AFTER =
      "select t.id, t.name, t.tag_group_id, t.colour, t.icon, t.merged_into, t.created_at"
          + " from tagging.tag t"
          + " join tagging.tag_assignment a on a.tenant_id = t.tenant_id and a.tag_id = t.id"
          + " where t.tenant_id = ? and a.item_id = ?"
          + " and (t.created_at > ? or (t.created_at = ? and t.id > ?))"
          + " order by t.created_at, t.id limit ?";
  private static final String TAGS_OF_LOCATION =
      "select t.id, t.name, t.tag_group_id, t.colour, t.icon, t.merged_into, t.created_at"
          + " from tagging.tag t"
          + " join tagging.tag_assignment a on a.tenant_id = t.tenant_id and a.tag_id = t.id"
          + " where t.tenant_id = ? and a.location_id = ?"
          + " order by t.created_at, t.id limit ?";
  private static final String TAGS_OF_LOCATION_AFTER =
      "select t.id, t.name, t.tag_group_id, t.colour, t.icon, t.merged_into, t.created_at"
          + " from tagging.tag t"
          + " join tagging.tag_assignment a on a.tenant_id = t.tenant_id and a.tag_id = t.id"
          + " where t.tenant_id = ? and a.location_id = ?"
          + " and (t.created_at > ? or (t.created_at = ? and t.id > ?))"
          + " order by t.created_at, t.id limit ?";
  private static final String TAG_GROUP_CURSOR = "tag-groups";

  private static final String TAG_COLUMNS =
      "select id, name, tag_group_id, colour, icon, merged_into, created_at from tagging.tag";
  private static final String TAG_PAGE =
      TAG_COLUMNS
          + " where tenant_id = ? and merged_into is null order by created_at, id limit ?";
  private static final String TAG_PAGE_AFTER =
      TAG_COLUMNS
          + " where tenant_id = ? and merged_into is null"
          + " and (created_at > ? or (created_at = ? and id > ?)) order by created_at, id limit ?";
  private static final String GROUP_COLUMNS =
      "select id, key, labels::text as labels, exclusive, display_order, created_at"
          + " from tagging.tag_group";
  private static final String GROUP_PAGE =
      GROUP_COLUMNS + " where tenant_id = ? order by created_at, id limit ?";
  private static final String GROUP_PAGE_AFTER =
      GROUP_COLUMNS
          + " where tenant_id = ? and (created_at > ? or (created_at = ? and id > ?))"
          + " order by created_at, id limit ?";

  private final JdbcClient jdbc;
  private final ObjectMapper mapper;
  private final CursorCodec cursors;
  private final ApplicationEventPublisher events;

  /** Where the last row read sat, for the next page's cursor. */
  private CursorCodec.Position position;

  @Override
  @Transactional
  public TagView create(CreateTagCommand command, UUID actor) {
    UUID tenantId = TenantContext.require();
    requireName(command.name());
    if (nameTaken(command.name(), null)) {
      throw new TagNameTakenException("tag", command.name());
    }
    UUID tagId = UUID.randomUUID();
    jdbc.sql(
            """
            insert into tagging.tag
                (id, tenant_id, name, tag_group_id, colour, icon, created_by, updated_by)
            values (?, ?, ?, ?, ?, ?, ?, ?)
            """)
        .params(
            tagId,
            tenantId,
            command.name().trim(),
            command.groupId(),
            command.colour(),
            command.icon(),
            actor,
            actor)
        .update();
    events.publishEvent(new TagCreated(tenantId, tagId, command.name().trim()));
    return tag(tagId);
  }

  @Override
  @Transactional(readOnly = true)
  public TagPage tags(String cursor, int limit) {
    int size = Math.clamp(limit, 1, MAX_PAGE);
    List<TagView> rows = page(TAG_PAGE, TAG_PAGE_AFTER, cursor, size, TAG_CURSOR, this::tagOf);
    return new TagPage(rows, nextCursor(rows.size(), size, TAG_CURSOR));
  }

  @Override
  @Transactional
  public TagView update(UUID tagId, UpdateTagCommand command, UUID actor) {
    UUID tenantId = TenantContext.require();
    TagView existing = tag(tagId);
    requireName(command.name());
    if (nameTaken(command.name(), tagId)) {
      throw new TagNameTakenException("tag", command.name());
    }
    jdbc.sql(
            """
            update tagging.tag
            set name = ?, tag_group_id = ?, colour = ?, icon = ?, updated_at = now(),
                updated_by = ?, version = version + 1
            where tenant_id = ? and id = ? and merged_into is null
            """)
        .params(
            command.name().trim(),
            command.groupId(),
            command.colour(),
            command.icon(),
            actor,
            tenantId,
            existing.id())
        .update();
    return tag(tagId);
  }

  @Override
  @Transactional
  public TagView merge(UUID sourceId, UUID targetId, UUID actor) {
    UUID tenantId = TenantContext.require();
    if (sourceId.equals(targetId)) {
      throw new IllegalArgumentException("A tag cannot be merged into itself.");
    }
    tag(sourceId);
    tag(targetId);

    // The duplicates go first: where both tags sat on one thing, moving the
    // source would collide with the unique index, and the answer is that the
    // thing already carries what it is being given.
    jdbc.sql(
            """
            delete from tagging.tag_assignment source
            where source.tenant_id = ? and source.tag_id = ?
              and exists (
                  select 1 from tagging.tag_assignment target
                  where target.tenant_id = source.tenant_id
                    and target.tag_id = ?
                    and target.item_id is not distinct from source.item_id
                    and target.location_id is not distinct from source.location_id)
            """)
        .params(tenantId, sourceId, targetId)
        .update();

    int moved =
        jdbc.sql(
                """
                update tagging.tag_assignment
                set tag_id = ?, version = version + 1
                where tenant_id = ? and tag_id = ?
                """)
            .params(targetId, tenantId, sourceId)
            .update();

    // A tombstone, not a deletion: a client holding the old id is redirected
    // rather than told the tag never existed (REQ-CORE-063).
    jdbc.sql(
            """
            update tagging.tag
            set merged_into = ?, updated_at = now(), updated_by = ?, version = version + 1
            where tenant_id = ? and id = ?
            """)
        .params(targetId, actor, tenantId, sourceId)
        .update();

    events.publishEvent(new TagMerged(tenantId, sourceId, targetId, moved));
    log.info("Tag {} merged into {} in tenant {}; {} assignment(s) moved", sourceId, targetId, tenantId, moved);
    return tag(targetId);
  }

  @Override
  @Transactional
  public void assign(UUID tagId, TagTarget target, UUID targetId, UUID actor) {
    UUID tenantId = TenantContext.require();
    TagView tag = live(tagId);

    // An exclusive group holds one tag per thing. Whatever else of this group is
    // on the target comes off first, and says so, so a consumer does not believe
    // the thing carries both.
    if (tag.groupId() != null && exclusive(tag.groupId())) {
      for (UUID displaced : otherTagsOfGroup(tag.groupId(), tagId, target, targetId)) {
        removeAssignment(displaced, target, targetId);
        events.publishEvent(new TagUnassigned(tenantId, displaced, target, targetId));
      }
    }

    int written =
        jdbc.sql(
                """
                insert into tagging.tag_assignment
                    (id, tenant_id, tag_id, item_id, location_id, created_by)
                values (?, ?, ?, ?, ?, ?)
                on conflict do nothing
                """)
            .params(
                UUID.randomUUID(),
                tenantId,
                tagId,
                target == TagTarget.ITEM ? targetId : null,
                target == TagTarget.LOCATION ? targetId : null,
                actor)
            .update();
    // Idempotent: a retry writes nothing and raises nothing, and only a real
    // assignment produces an event.
    if (written > 0) {
      events.publishEvent(new TagAssigned(tenantId, tagId, target, targetId));
    }
  }

  @Override
  @Transactional
  public void unassign(UUID tagId, TagTarget target, UUID targetId, UUID actor) {
    UUID tenantId = TenantContext.require();
    if (removeAssignment(tagId, target, targetId) > 0) {
      events.publishEvent(new TagUnassigned(tenantId, tagId, target, targetId));
    }
  }

  @Override
  @Transactional(readOnly = true)
  public TagPage tagsOf(TagTarget target, UUID targetId, String cursor, int limit) {
    UUID tenantId = TenantContext.require();
    int size = Math.clamp(limit, 1, MAX_PAGE);
    position = null;

    List<TagView> rows;
    if (cursor == null || cursor.isBlank()) {
      rows =
          jdbc.sql(target == TagTarget.ITEM ? TAGS_OF_ITEM : TAGS_OF_LOCATION)
              .params(tenantId, targetId, size)
              .query((rs, rowNum) -> tagOf(rs, rowNum))
              .list();
    } else {
      CursorCodec.Position from = cursors.decode(cursor, ASSIGNED_CURSOR);
      rows =
          jdbc.sql(target == TagTarget.ITEM ? TAGS_OF_ITEM_AFTER : TAGS_OF_LOCATION_AFTER)
              .params(tenantId, targetId, from.createdAt(), from.createdAt(), from.id(), size)
              .query((rs, rowNum) -> tagOf(rs, rowNum))
              .list();
    }
    return new TagPage(rows, nextCursor(rows.size(), size, ASSIGNED_CURSOR));
  }

  @Override
  @Transactional
  public TagGroupView createGroup(CreateTagGroupCommand command, UUID actor) {
    UUID tenantId = TenantContext.require();
    if (command.key() == null || !command.key().matches("^[a-z][A-Za-z0-9-]*$")) {
      throw new IllegalArgumentException(
          "A group key starts with a lower-case letter and continues with letters, digits or "
              + "hyphens. It travels in the export, so it is not a label.");
    }
    boolean taken =
        jdbc.sql("select count(*) from tagging.tag_group where tenant_id = ? and key = ?")
                .params(tenantId, command.key())
                .query(Long.class)
                .single()
            > 0;
    if (taken) {
      throw new TagNameTakenException("tag group", command.key());
    }
    UUID groupId = UUID.randomUUID();
    jdbc.sql(
            """
            insert into tagging.tag_group
                (id, tenant_id, key, labels, exclusive, display_order, created_by, updated_by)
            values (?, ?, ?, ?::jsonb, ?, ?, ?, ?)
            """)
        .params(
            groupId,
            tenantId,
            command.key(),
            mapper.writeValueAsString(command.labels() == null ? Map.of() : command.labels()),
            command.exclusive(),
            command.displayOrder(),
            actor,
            actor)
        .update();
    return group(groupId);
  }

  @Override
  @Transactional(readOnly = true)
  public TagGroupPage groups(String cursor, int limit) {
    int size = Math.clamp(limit, 1, MAX_PAGE);
    List<TagGroupView> rows =
        page(GROUP_PAGE, GROUP_PAGE_AFTER, cursor, size, TAG_GROUP_CURSOR, this::groupOf);
    return new TagGroupPage(rows, nextCursor(rows.size(), size, TAG_GROUP_CURSOR));
  }

  // -------------------------------------------------------------------------
  // Internals
  // -------------------------------------------------------------------------

  /**
   * Removes one assignment.
   *
   * @param tagId the tag
   * @param target what kind of thing it is on
   * @param targetId the item or place
   * @return how many rows went, which is zero or one
   */
  private int removeAssignment(UUID tagId, TagTarget target, UUID targetId) {
    UUID tenantId = TenantContext.require();
    return target == TagTarget.ITEM
        ? jdbc.sql(
                """
                delete from tagging.tag_assignment
                where tenant_id = ? and tag_id = ? and item_id = ?
                """)
            .params(tenantId, tagId, targetId)
            .update()
        : jdbc.sql(
                """
                delete from tagging.tag_assignment
                where tenant_id = ? and tag_id = ? and location_id = ?
                """)
            .params(tenantId, tagId, targetId)
            .update();
  }

  /**
   * The other tags of one group that a thing already carries.
   *
   * @param groupId the group
   * @param keep the tag being assigned, which is not displaced by itself
   * @param target what kind of thing
   * @param targetId the item or place
   * @return the tags to remove
   */
  private List<UUID> otherTagsOfGroup(UUID groupId, UUID keep, TagTarget target, UUID targetId) {
    UUID tenantId = TenantContext.require();
    String sql =
        target == TagTarget.ITEM
            ? """
              select t.id from tagging.tag t
              join tagging.tag_assignment a on a.tenant_id = t.tenant_id and a.tag_id = t.id
              where t.tenant_id = ? and t.tag_group_id = ? and t.id <> ? and a.item_id = ?
              """
            : """
              select t.id from tagging.tag t
              join tagging.tag_assignment a on a.tenant_id = t.tenant_id and a.tag_id = t.id
              where t.tenant_id = ? and t.tag_group_id = ? and t.id <> ? and a.location_id = ?
              """;
    return jdbc.sql(sql).params(tenantId, groupId, keep, targetId).query(UUID.class).list();
  }

  /**
   * Whether a group holds at most one tag per thing.
   *
   * @param groupId the group
   * @return true when it is exclusive
   */
  private boolean exclusive(UUID groupId) {
    return Boolean.TRUE.equals(
        jdbc.sql("select exclusive from tagging.tag_group where tenant_id = ? and id = ?")
            .params(TenantContext.require(), groupId)
            .query(Boolean.class)
            .optional()
            .orElse(false));
  }

  /**
   * Whether a live tag already carries a name.
   *
   * @param name the proposed name
   * @param exclude the tag being renamed, so it does not conflict with itself
   * @return true when the name is taken
   */
  private boolean nameTaken(String name, UUID exclude) {
    return jdbc
            .sql(
                """
                select count(*) from tagging.tag
                where tenant_id = ? and merged_into is null and lower(name) = lower(?)
                  and (?::uuid is null or id <> ?)
                """)
            .params(TenantContext.require(), name.trim(), exclude, exclude)
            .query(Long.class)
            .single()
        > 0;
  }

  /**
   * Refuses a name the database would refuse, before the insert.
   *
   * @param name the proposed name
   */
  private void requireName(String name) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("A tag needs a name.");
    }
  }

  /**
   * One tag, tombstone or not.
   *
   * @param tagId the tag
   * @return the view
   */
  private TagView tag(UUID tagId) {
    return jdbc
        .sql(TAG_COLUMNS + " where tenant_id = ? and id = ?")
        .params(TenantContext.require(), tagId)
        .query((rs, rowNum) -> tagOf(rs, rowNum))
        .optional()
        .orElseThrow(() -> new NotFoundException("tag", tagId));
  }

  /**
   * One tag that is still itself.
   *
   * @param tagId the tag
   * @return the view
   * @throws NotFoundException when it does not exist or has been merged away
   */
  private TagView live(UUID tagId) {
    TagView tag = tag(tagId);
    if (tag.mergedInto() != null) {
      throw new NotFoundException("tag", tagId);
    }
    return tag;
  }

  /**
   * One group.
   *
   * @param groupId the group
   * @return the view
   */
  private TagGroupView group(UUID groupId) {
    return jdbc
        .sql(GROUP_COLUMNS + " where tenant_id = ? and id = ?")
        .params(TenantContext.require(), groupId)
        .query((rs, rowNum) -> groupOf(rs, rowNum))
        .optional()
        .orElseThrow(() -> new NotFoundException("tag group", groupId));
  }

  /**
   * Maps a tag row.
   *
   * @param rs the row
   * @param rowNum which row, as the mapper contract takes it
   * @return the view
   * @throws SQLException when the row cannot be read
   */
  private TagView tagOf(ResultSet rs, int rowNum) throws SQLException {
    position =
        new CursorCodec.Position(
            rs.getTimestamp("created_at").toInstant(), rs.getObject("id", UUID.class));
    return new TagView(
        rs.getObject("id", UUID.class),
        rs.getString("name"),
        rs.getObject("tag_group_id", UUID.class),
        rs.getString("colour"),
        rs.getString("icon"),
        rs.getObject("merged_into", UUID.class));
  }

  /**
   * Maps a group row.
   *
   * @param rs the row
   * @param rowNum which row
   * @return the view
   * @throws SQLException when the row cannot be read
   */
  private TagGroupView groupOf(ResultSet rs, int rowNum) throws SQLException {
    position =
        new CursorCodec.Position(
            rs.getTimestamp("created_at").toInstant(), rs.getObject("id", UUID.class));
    String labels = rs.getString("labels");
    Map<String, String> byLanguage = new LinkedHashMap<>();
    if (labels != null && !labels.isBlank()) {
      mapper.readTree(labels).properties()
          .forEach(entry -> byLanguage.put(entry.getKey(), entry.getValue().asString()));
    }
    return new TagGroupView(
        rs.getObject("id", UUID.class),
        rs.getString("key"),
        Map.copyOf(byLanguage),
        rs.getBoolean("exclusive"),
        rs.getInt("display_order"));
  }

  /**
   * Reads one page, from the start or from where a cursor left off.
   *
   * @param first the statement for the first page
   * @param after the statement for a later page
   * @param cursor the cursor, or {@code null}
   * @param size how many rows at most
   * @param fingerprint what the cursor must be bound to
   * @param mapper how to read a row
   * @param <T> what a row becomes
   * @return the rows
   */
  private <T> List<T> page(
      String first,
      String after,
      String cursor,
      int size,
      String fingerprint,
      org.springframework.jdbc.core.RowMapper<T> mapper) {
    UUID tenantId = TenantContext.require();
    position = null;
    if (cursor == null || cursor.isBlank()) {
      return jdbc.sql(first).params(tenantId, size).query(mapper).list();
    }
    CursorCodec.Position from = cursors.decode(cursor, fingerprint);
    return jdbc.sql(after)
        .params(tenantId, from.createdAt(), from.createdAt(), from.id(), size)
        .query(mapper)
        .list();
  }

  /**
   * The cursor for the next page, or none when this page was the last.
   *
   * @param read how many rows came back
   * @param size how many were asked for
   * @param fingerprint what the cursor is bound to
   * @return the cursor, or {@code null}
   */
  private String nextCursor(int read, int size, String fingerprint) {
    return read == size && position != null ? cursors.encode(position, fingerprint) : null;
  }
}
