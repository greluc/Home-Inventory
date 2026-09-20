/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.infrastructure;

import de.greluc.homeinv.platform.TenantContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the reconciliation reads (REQ-NFR-073, ADR-0004).
 *
 * <p>{@code item_attr_index} is <b>derived</b>: the source of truth is {@code item.attributes}, and
 * this table is an application-maintained projection of it. Derived stores may fail and may never
 * lie, which is only a property if something compares the two — this is the reading half of that
 * comparison.
 */
@Component
@RequiredArgsConstructor
public class AttributeIndexQueries {

  private final JdbcClient jdbc;

  /**
   * Every tenant that owns an item, so the nightly run knows where to look.
   *
   * <p>Instance-wide and therefore outside any tenant context: the run visits each tenant in turn
   * and sets the context itself. A tenant with no item has nothing to reconcile and is not returned,
   * which keeps a fresh instance's run free.
   *
   * @return the tenant ids
   */
  @Transactional(readOnly = true)
  public List<UUID> tenantsWithItems() {
    return jdbc.sql(
            """
            select distinct tenant_id
            from inventory.item
            where deleted_at is null
            order by tenant_id
            """)
        .query(UUID.class)
        .list();
  }

  /**
   * One page of items, in id order, for the current tenant.
   *
   * <p>Keyset rather than an offset, for the reason every listing here uses one: an offset re-reads
   * what it has already passed, and a run over a million items would spend its night doing that.
   *
   * @param after the last id of the previous page, or {@code null} to start
   * @param size how many at most
   * @return the items, with what they need to be projected
   */
  @Transactional(readOnly = true)
  public List<Projectable> page(UUID after, int size) {
    return jdbc.sql(
            """
            select id, item_type_version_id, attributes::text as attributes
            from inventory.item
            where tenant_id = ? and deleted_at is null
              -- The cast is not decoration: PostgreSQL cannot infer a type for a
              -- bare parameter in `? is null`, and the statement is rejected
              -- before it runs.
              and (?::uuid is null or id > ?::uuid)
            order by id
            limit ?
            """)
        .params(
            TenantContext.require(),
            after == null ? null : after.toString(),
            after == null ? null : after.toString(),
            size)
        .query(
            (rs, row) ->
                new Projectable(
                    rs.getObject("id", UUID.class),
                    rs.getObject("item_type_version_id", UUID.class),
                    rs.getString("attributes")))
        .list();
  }

  /**
   * What the side table currently holds for these items, by item.
   *
   * <p>One statement for the whole page rather than one per item: a reconciliation that issued a
   * query per row would take longer than the window it runs in.
   *
   * @param items the page's item ids
   * @return the projected field keys per item, sorted so a comparison is a set comparison
   */
  @Transactional(readOnly = true)
  public Map<UUID, Set<String>> projectedKeys(List<UUID> items) {
    Map<UUID, Set<String>> held = new LinkedHashMap<>();
    if (items.isEmpty()) {
      return held;
    }
    // `any(?::uuid[])` with a String[] parameter: a UUID[] binds to nothing here
    // and matches no row without saying so, which cost an afternoon once.
    String[] ids = items.stream().map(UUID::toString).toArray(String[]::new);
    jdbc.sql(
            """
            select item_id, field_key
            from inventory.item_attr_index
            where tenant_id = ? and item_id = any(?::uuid[])
            """)
        .params(TenantContext.require(), ids)
        .query(
            (rs, row) -> {
              held.computeIfAbsent(rs.getObject("item_id", UUID.class), key -> new TreeSet<>())
                  .add(rs.getString("field_key"));
              return null;
            })
        .list();
    return held;
  }

  /**
   * One item, as the projection sees it.
   *
   * @param id the item
   * @param typeVersionId the version whose field definitions decide what is projected
   * @param attributes the attribute set as JSON text
   */
  public record Projectable(UUID id, UUID typeVersionId, String attributes) {}
}
