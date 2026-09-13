/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.infrastructure;

import de.greluc.homeinv.inventory.api.BundleCycleException;
import de.greluc.homeinv.inventory.api.ItemBundles;
import de.greluc.homeinv.platform.CursorCodec;
import de.greluc.homeinv.platform.NotFoundException;
import de.greluc.homeinv.platform.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bundles over one directed table (REQ-CORE-007).
 *
 * <p>An edge says "this item contains that one" and nothing else — no location moves, because a
 * bundle is not a place. Both directions are read from the same row: what is in this bundle, and
 * which bundles this is in.
 *
 * <h2>The cycle check</h2>
 *
 * <p>An item may be in several bundles, so the containment graph is a DAG rather than a forest and
 * a cycle is a <em>reachability</em> question: adding "B contains M" closes a loop exactly when B
 * can already be reached from M. That is one recursive walk, in the database, inside the same
 * transaction as the insert — asking it in the application would mean reading the graph out and
 * racing every other writer.
 *
 * <p>The walk uses {@code UNION} rather than {@code UNION ALL}. The distinct step is what makes it
 * terminate on a graph that already has a cycle, which this one cannot have and which is exactly
 * the state where a non-terminating query would be least welcome.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ItemBundleAdapter implements ItemBundles {

  /** The cap REQ-NFR-010 puts on every page of every collection. */
  private static final int MAX_PAGE = 200;

  /** What a contents cursor is bound to, so one listing's cursor cannot resume the other. */
  private static final String CONTENTS_CURSOR = "item-bundle-contents";

  /** What a "which bundles am I in" cursor is bound to. */
  private static final String BUNDLES_CURSOR = "item-bundle-membership";

  /**
   * Whether the prospective bundle can already be reached from the prospective member.
   *
   * <p>Reads as: start at everything the member contains, keep following what those contain, and
   * say whether the bundle is among them. True means the edge about to be written would close the
   * loop.
   */
  private static final String WOULD_CLOSE_A_LOOP =
      """
      with recursive reachable(id) as (
          select member_item_id
          from inventory.item_bundle
          where tenant_id = ? and bundle_item_id = ?
        union
          select deeper.member_item_id
          from inventory.item_bundle deeper
          join reachable on deeper.bundle_item_id = reachable.id
          where deeper.tenant_id = ?
      )
      select exists (select 1 from reachable where id = ?)
      """;

  /**
   * What a bundle directly contains, newest membership last.
   *
   * <p>Joined to the item so that something in the trash is left out: it is restorable, and a list
   * of what is in a box must not offer what is not in it any more.
   */
  private static final String CONTENTS =
      """
      select b.id, b.bundle_item_id, b.member_item_id, b.created_at
      from inventory.item_bundle b
      join inventory.item i on i.tenant_id = b.tenant_id and i.id = b.member_item_id
      where b.tenant_id = ? and b.bundle_item_id = ? and i.deleted_at is null
      order by b.created_at, b.id
      limit ?
      """;

  private static final String CONTENTS_AFTER =
      """
      select b.id, b.bundle_item_id, b.member_item_id, b.created_at
      from inventory.item_bundle b
      join inventory.item i on i.tenant_id = b.tenant_id and i.id = b.member_item_id
      where b.tenant_id = ? and b.bundle_item_id = ? and i.deleted_at is null
        and (b.created_at > ? or (b.created_at = ? and b.id > ?))
      order by b.created_at, b.id
      limit ?
      """;

  /** The same rows read from the other end: which bundles hold this item. */
  private static final String BUNDLES =
      """
      select b.id, b.bundle_item_id, b.member_item_id, b.created_at
      from inventory.item_bundle b
      join inventory.item i on i.tenant_id = b.tenant_id and i.id = b.bundle_item_id
      where b.tenant_id = ? and b.member_item_id = ? and i.deleted_at is null
      order by b.created_at, b.id
      limit ?
      """;

  private static final String BUNDLES_AFTER =
      """
      select b.id, b.bundle_item_id, b.member_item_id, b.created_at
      from inventory.item_bundle b
      join inventory.item i on i.tenant_id = b.tenant_id and i.id = b.bundle_item_id
      where b.tenant_id = ? and b.member_item_id = ? and i.deleted_at is null
        and (b.created_at > ? or (b.created_at = ? and b.id > ?))
      order by b.created_at, b.id
      limit ?
      """;

  private final JdbcClient jdbc;
  private final CursorCodec cursors;
  private final ItemRepository items;

  @Override
  @Transactional
  public BundleMemberView add(UUID bundleId, UUID memberId, UUID actor) {
    UUID tenantId = TenantContext.require();
    if (bundleId.equals(memberId)) {
      throw new BundleCycleException("A bundle cannot contain itself.");
    }
    // Both ends here rather than left to the foreign keys, so that naming
    // something this tenant cannot see is a 404 and not a constraint violation
    // surfacing as a 500. `findLive`, because putting a trashed item into a
    // bundle is a request about something that is on its way out.
    items.findLive(tenantId, bundleId).orElseThrow(() -> new NotFoundException("item", bundleId));
    items.findLive(tenantId, memberId).orElseThrow(() -> new NotFoundException("item", memberId));
    requireNoLoop(tenantId, bundleId, memberId);

    jdbc.sql(
            """
            insert into inventory.item_bundle
                (id, tenant_id, bundle_item_id, member_item_id, created_by)
            values (?, ?, ?, ?, ?)
            on conflict do nothing
            """)
        .params(UUID.randomUUID(), tenantId, bundleId, memberId, actor)
        .update();

    return jdbc
        .sql(
            """
            select id, bundle_item_id, member_item_id
            from inventory.item_bundle
            where tenant_id = ? and bundle_item_id = ? and member_item_id = ?
            """)
        .params(tenantId, bundleId, memberId)
        .query(
            (rs, rowNum) ->
                new BundleMemberView(
                    rs.getObject("id", UUID.class),
                    rs.getObject("bundle_item_id", UUID.class),
                    rs.getObject("member_item_id", UUID.class)))
        .single();
  }

  @Override
  @Transactional
  public void remove(UUID bundleId, UUID memberId, UUID actor) {
    int removed =
        jdbc.sql(
                """
                delete from inventory.item_bundle
                where tenant_id = ? and bundle_item_id = ? and member_item_id = ?
                """)
            .params(TenantContext.require(), bundleId, memberId)
            .update();
    if (removed > 0) {
      log.debug("Item {} taken out of bundle {} by {}", memberId, bundleId, actor);
    }
  }

  @Override
  @Transactional(readOnly = true)
  public BundleMemberPage contentsOf(UUID bundleId, String cursor, int limit) {
    UUID tenantId = TenantContext.require();
    items.findAny(tenantId, bundleId).orElseThrow(() -> new NotFoundException("item", bundleId));
    return page(tenantId, bundleId, cursor, limit, CONTENTS, CONTENTS_AFTER, CONTENTS_CURSOR);
  }

  @Override
  @Transactional(readOnly = true)
  public BundleMemberPage bundlesOf(UUID memberId, String cursor, int limit) {
    UUID tenantId = TenantContext.require();
    items.findAny(tenantId, memberId).orElseThrow(() -> new NotFoundException("item", memberId));
    return page(tenantId, memberId, cursor, limit, BUNDLES, BUNDLES_AFTER, BUNDLES_CURSOR);
  }

  // -------------------------------------------------------------------------

  /**
   * Refuses the edge that would close a loop.
   *
   * @param tenantId the tenant
   * @param bundleId the item that would contain
   * @param memberId the item that would go into it
   * @throws BundleCycleException when the bundle already sits inside the member
   */
  private void requireNoLoop(UUID tenantId, UUID bundleId, UUID memberId) {
    Boolean loops =
        jdbc.sql(WOULD_CLOSE_A_LOOP)
            .params(tenantId, memberId, tenantId, bundleId)
            .query(Boolean.class)
            .single();
    if (Boolean.TRUE.equals(loops)) {
      throw new BundleCycleException(
          "That bundle is already inside the item being put into it, so this would make it contain "
              + "itself.");
    }
  }

  /**
   * One page of memberships, from the start or from where a cursor left off.
   *
   * <p>The cursor comes from the <b>last row returned</b> rather than from a field the row mapper
   * wrote to. A mapper writing to the adapter would be writing to a singleton, and two requests in
   * flight would then hand each other's positions out — a wrong cursor being the kind of defect
   * that shows up as a page quietly skipped under load and never in a test.
   *
   * @param tenantId the tenant
   * @param itemId the item at the end being asked about
   * @param cursor an opaque cursor from a previous page, or {@code null}
   * @param limit how many at most
   * @param first the statement for the first page
   * @param after the statement for a later page
   * @param fingerprint what the cursor is bound to
   * @return the page
   */
  private BundleMemberPage page(
      UUID tenantId,
      UUID itemId,
      String cursor,
      int limit,
      String first,
      String after,
      String fingerprint) {

    int size = Math.clamp(limit, 1, MAX_PAGE);
    List<Row> rows;
    if (cursor == null || cursor.isBlank()) {
      rows = jdbc.sql(first).params(tenantId, itemId, size).query(Row.class).list();
    } else {
      CursorCodec.Position from = cursors.decode(cursor, fingerprint);
      // Wrapped, not passed as an Instant. The driver cannot infer a SQL type
      // for java.time.Instant and answers "bad SQL grammar" for a statement that
      // is perfectly good -- on the SECOND page only, which is why an adapter
      // tested with one page at a time can carry this for months.
      java.sql.Timestamp at = java.sql.Timestamp.from(from.createdAt());
      rows =
          jdbc.sql(after)
              .params(tenantId, itemId, at, at, from.id(), size)
              .query(Row.class)
              .list();
    }

    String next = null;
    if (rows.size() == size) {
      Row last = rows.getLast();
      next =
          cursors.encode(
              new CursorCodec.Position(last.createdAt(), last.id()), fingerprint);
    }
    return new BundleMemberPage(
        rows.stream()
            .map(row -> new BundleMemberView(row.id(), row.bundleItemId(), row.memberItemId()))
            .toList(),
        next);
  }

  /**
   * One membership row, with the pair the keyset cursor resumes from.
   *
   * @param id the membership
   * @param bundleItemId the item that contains
   * @param memberItemId the item in it
   * @param createdAt when the membership was made, the position half of the cursor
   */
  public record Row(UUID id, UUID bundleItemId, UUID memberItemId, Instant createdAt) {}
}
