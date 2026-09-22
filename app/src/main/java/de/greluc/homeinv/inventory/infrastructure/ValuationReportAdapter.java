/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.infrastructure;

import de.greluc.homeinv.inventory.api.ValuationReport;
import de.greluc.homeinv.platform.Money;
import de.greluc.homeinv.platform.TenantContext;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The valuation report, in SQL (REQ-LIFE-008, REQ-LIFE-015, REQ-LIFE-017).
 *
 * <h2>One query per dimension, and no string building</h2>
 *
 * <p>The three statements differ only in the column they group by, which is exactly the shape that
 * invites a concatenated {@code group by " + column}. {@code ArchitectureRulesTest} refuses a SQL
 * literal joined by {@code +} to anything, because that is the one shape through which a value can
 * enter a statement (REQ-SEC-031), so they are written out. Three near-identical literals is the
 * cheaper half of that trade.
 *
 * <p>Each is a {@code UNION ALL} over the three figures rather than three round trips: they are
 * independent columns with their own currency (REQ-LIFE-014), so they cannot share a {@code sum},
 * and a row of the union names which figure it is.
 *
 * <h2>What is counted</h2>
 *
 * <p>Things the tenant still has. A trashed item is on its way out and a sold one is gone, and
 * "what is the house worth" that included the bicycle sold in March would be wrong in a way nobody
 * could see. {@code ARCHIVED} <b>is</b> counted: it is out of everyday use and still owned.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ValuationReportAdapter implements ValuationReport {

  /** The cap REQ-NFR-010 puts on every collection. */
  private static final int MAX_ROWS = 200;

  private static final String BY_LOCATION =
      """
      select 'PURCHASE' as figure, location_id as gid, purchase_currency as cur,
             sum(purchase_amount) as total
        from inventory.item
       where tenant_id = ? and deleted_at is null
         and lifecycle_state in ('ACTIVE', 'LENT', 'ARCHIVED')
         and location_id is not null and purchase_amount is not null
       group by location_id, purchase_currency
      union all
      select 'CURRENT', location_id, current_currency, sum(current_amount)
        from inventory.item
       where tenant_id = ? and deleted_at is null
         and lifecycle_state in ('ACTIVE', 'LENT', 'ARCHIVED')
         and location_id is not null and current_amount is not null
       group by location_id, current_currency
      union all
      select 'REPLACEMENT', location_id, replacement_currency, sum(replacement_amount)
        from inventory.item
       where tenant_id = ? and deleted_at is null
         and lifecycle_state in ('ACTIVE', 'LENT', 'ARCHIVED')
         and location_id is not null and replacement_amount is not null
       group by location_id, replacement_currency
      """;

  private static final String COUNT_BY_LOCATION =
      """
      select location_id as gid, count(*) as n
        from inventory.item
       where tenant_id = ? and deleted_at is null
         and lifecycle_state in ('ACTIVE', 'LENT', 'ARCHIVED')
         and location_id is not null
       group by location_id
      """;

  private static final String BY_TYPE =
      """
      select 'PURCHASE' as figure, item_type_version_id as gid, purchase_currency as cur,
             sum(purchase_amount) as total
        from inventory.item
       where tenant_id = ? and deleted_at is null
         and lifecycle_state in ('ACTIVE', 'LENT', 'ARCHIVED')
         and purchase_amount is not null
       group by item_type_version_id, purchase_currency
      union all
      select 'CURRENT', item_type_version_id, current_currency, sum(current_amount)
        from inventory.item
       where tenant_id = ? and deleted_at is null
         and lifecycle_state in ('ACTIVE', 'LENT', 'ARCHIVED')
         and current_amount is not null
       group by item_type_version_id, current_currency
      union all
      select 'REPLACEMENT', item_type_version_id, replacement_currency, sum(replacement_amount)
        from inventory.item
       where tenant_id = ? and deleted_at is null
         and lifecycle_state in ('ACTIVE', 'LENT', 'ARCHIVED')
         and replacement_amount is not null
       group by item_type_version_id, replacement_currency
      """;

  private static final String COUNT_BY_TYPE =
      """
      select item_type_version_id as gid, count(*) as n
        from inventory.item
       where tenant_id = ? and deleted_at is null
         and lifecycle_state in ('ACTIVE', 'LENT', 'ARCHIVED')
       group by item_type_version_id
      """;

  private static final String FOR_ITEMS =
      """
      select 'PURCHASE' as figure, purchase_currency as cur, sum(purchase_amount) as total
        from inventory.item
       where tenant_id = :tenant and deleted_at is null
         and lifecycle_state in ('ACTIVE', 'LENT', 'ARCHIVED')
         and id in (:ids) and purchase_amount is not null
       group by purchase_currency
      union all
      select 'CURRENT', current_currency, sum(current_amount)
        from inventory.item
       where tenant_id = :tenant and deleted_at is null
         and lifecycle_state in ('ACTIVE', 'LENT', 'ARCHIVED')
         and id in (:ids) and current_amount is not null
       group by current_currency
      union all
      select 'REPLACEMENT', replacement_currency, sum(replacement_amount)
        from inventory.item
       where tenant_id = :tenant and deleted_at is null
         and lifecycle_state in ('ACTIVE', 'LENT', 'ARCHIVED')
         and id in (:ids) and replacement_amount is not null
       group by replacement_currency
      """;

  private static final String COUNT_FOR_ITEMS =
      """
      select count(*) as n
        from inventory.item
       where tenant_id = :tenant and deleted_at is null
         and lifecycle_state in ('ACTIVE', 'LENT', 'ARCHIVED')
         and id in (:ids)
      """;

  private final JdbcClient jdbc;
  private final de.greluc.homeinv.inventory.api.PlaceTree places;
  private final de.greluc.homeinv.catalog.api.TypeRegistry types;
  private final de.greluc.homeinv.tagging.api.TagService tagService;
  private final de.greluc.homeinv.tagging.api.TagQueries tagQueries;

  @Override
  @Transactional(readOnly = true)
  public ValuationSummary byLocation(UUID root, int limit) {
    UUID tenantId = TenantContext.require();
    Map<UUID, Figures> own = grouped(BY_LOCATION, tenantId);
    Map<UUID, Long> counts = counted(COUNT_BY_LOCATION, tenantId);

    // THE ROLL-UP. Each place's own figures are added to itself and to every one
    // of its ancestors, so one pass over the places that actually hold something
    // gives a subtree total for every place above them. Walking down instead
    // would ask the tree about places that hold nothing, which is most of them.
    Map<UUID, Figures> subtree = new HashMap<>();
    Map<UUID, Long> subtreeCounts = new HashMap<>();
    Set<UUID> inScope = root == null ? null : Set.copyOf(places.subtreeOf(root));

    for (Map.Entry<UUID, Figures> entry : own.entrySet()) {
      UUID place = entry.getKey();
      // `ancestorIds` is "root first, ITSELF LAST" -- the place is already in the
      // list, and adding it again counted everything directly in a room twice.
      for (UUID at : places.ancestorsOf(place)) {
        subtree.merge(at, entry.getValue(), Figures::plus);
        subtreeCounts.merge(at, counts.getOrDefault(place, 0L), Long::sum);
      }
    }

    List<ValuationRow> rows = new ArrayList<>();
    for (Map.Entry<UUID, Figures> rolled : subtree.entrySet()) {
      UUID place = rolled.getKey();
      if (inScope != null && !inScope.contains(place)) {
        continue;
      }
      rows.add(
          new ValuationRow(
              place,
              labelOfPlace(place),
              own.getOrDefault(place, Figures.NONE),
              rolled.getValue(),
              counts.getOrDefault(place, 0L),
              subtreeCounts.getOrDefault(place, 0L)));
    }
    return report("location", rows, limit, false);
  }

  @Override
  @Transactional(readOnly = true)
  public ValuationSummary byType(int limit) {
    UUID tenantId = TenantContext.require();
    Map<UUID, Figures> perVersion = grouped(BY_TYPE, tenantId);
    Map<UUID, Long> countsPerVersion = counted(COUNT_BY_TYPE, tenantId);

    // An item written last year is still a power tool: the figures are collected
    // per type VERSION and merged per type, which is the argument 08 §8.2 makes
    // about the `type:` filter naming a key rather than a version.
    Set<UUID> versions = union(perVersion.keySet(), countsPerVersion.keySet());
    // Two published reads rather than one: the type an item's version belongs to,
    // and that type's key for the label. `TypeIdentity` carries the key and not
    // the id, which is the same choice 08 §8.2 makes for the `type:` filter -- a
    // key is what a person writes.
    Map<UUID, UUID> typeOfVersion = types.itemTypesOfVersions(versions);
    Map<UUID, de.greluc.homeinv.catalog.api.TypeRegistry.TypeIdentity> identities =
        types.typesOfVersions(versions);

    Map<UUID, Figures> perType = new LinkedHashMap<>();
    Map<UUID, Long> perTypeCounts = new LinkedHashMap<>();
    Map<UUID, String> labels = new HashMap<>();
    for (UUID version : versions) {
      UUID typeId = typeOfVersion.get(version);
      if (typeId == null) {
        continue;
      }
      de.greluc.homeinv.catalog.api.TypeRegistry.TypeIdentity identity = identities.get(version);
      if (identity != null) {
        labels.putIfAbsent(typeId, identity.key());
      }
      Figures figures = perVersion.get(version);
      if (figures != null) {
        perType.merge(typeId, figures, Figures::plus);
      }
      perTypeCounts.merge(typeId, countsPerVersion.getOrDefault(version, 0L), Long::sum);
    }

    List<ValuationRow> rows = new ArrayList<>();
    for (Map.Entry<UUID, Long> entry : perTypeCounts.entrySet()) {
      Figures figures = perType.getOrDefault(entry.getKey(), Figures.NONE);
      // No tree here, so `subtree` repeats `own` rather than being absent: one
      // shape reads the same whichever dimension produced it.
      rows.add(
          new ValuationRow(
              entry.getKey(),
              labels.getOrDefault(entry.getKey(), "?"),
              figures,
              figures,
              entry.getValue(),
              entry.getValue()));
    }
    return report("type", rows, limit, false);
  }

  @Override
  @Transactional(readOnly = true)
  public ValuationSummary byTag(int limit) {
    UUID tenantId = TenantContext.require();
    List<ValuationRow> rows = new ArrayList<>();

    // ONE QUERY PER TAG, and bounded by the page rather than by the inventory.
    // `tagging` owns which things carry a tag and this block must not read its
    // table (ADR-0002), so the ids come through its published port and the money
    // is summed here. A household has tens of tags; a join would be cheaper and
    // would be a block reading another's schema.
    for (de.greluc.homeinv.tagging.api.TagView tag :
        tagService.tags(null, Math.clamp(limit, 1, MAX_ROWS)).data()) {
      List<UUID> tagged = tagQueries.itemsTagged(List.of(tag.name()));
      if (tagged.isEmpty()) {
        continue;
      }
      Figures figures = figuresFor(tenantId, tagged);
      long count = countFor(tenantId, tagged);
      rows.add(new ValuationRow(tag.id(), tag.name(), figures, figures, count, count));
    }
    // OVERLAPPING: an item with three tags is in three rows, so the column does
    // not add up to the tenant's total. The report says so rather than leaving a
    // reader to find out by adding it up.
    return report("tag", rows, limit, true);
  }

  // -------------------------------------------------------------------------

  private Map<UUID, Figures> grouped(String sql, UUID tenantId) {
    Map<UUID, Map<String, List<Money>>> collected = new HashMap<>();
    jdbc.sql(sql)
        .param(tenantId)
        .param(tenantId)
        .param(tenantId)
        .query(
            (ResultSet rs, int row) -> {
              collected
                  .computeIfAbsent(rs.getObject("gid", UUID.class), ignored -> new HashMap<>())
                  .computeIfAbsent(rs.getString("figure"), ignored -> new ArrayList<>())
                  .add(money(rs));
              return null;
            })
        .list();
    Map<UUID, Figures> figures = new HashMap<>();
    collected.forEach((gid, perFigure) -> figures.put(gid, toFigures(perFigure)));
    return figures;
  }

  private Map<UUID, Long> counted(String sql, UUID tenantId) {
    Map<UUID, Long> counts = new HashMap<>();
    jdbc.sql(sql)
        .param(tenantId)
        .query(
            (ResultSet rs, int row) -> {
              counts.put(rs.getObject("gid", UUID.class), rs.getLong("n"));
              return null;
            })
        .list();
    return counts;
  }

  private Figures figuresFor(UUID tenantId, List<UUID> ids) {
    Map<String, List<Money>> perFigure = new HashMap<>();
    jdbc.sql(FOR_ITEMS)
        .param("tenant", tenantId)
        .param("ids", ids)
        .query(
            (ResultSet rs, int row) -> {
              perFigure
                  .computeIfAbsent(rs.getString("figure"), ignored -> new ArrayList<>())
                  .add(money(rs));
              return null;
            })
        .list();
    return toFigures(perFigure);
  }

  private long countFor(UUID tenantId, List<UUID> ids) {
    Long count =
        jdbc.sql(COUNT_FOR_ITEMS).param("tenant", tenantId).param("ids", ids).query(Long.class)
            .optional()
            .orElse(0L);
    return count == null ? 0L : count;
  }

  private static Money money(ResultSet rs) throws SQLException {
    BigDecimal total = rs.getBigDecimal("total");
    return new Money(total, Currency.getInstance(rs.getString("cur")));
  }

  private static Figures toFigures(Map<String, List<Money>> perFigure) {
    return new Figures(
        perFigure.getOrDefault("PURCHASE", List.of()),
        perFigure.getOrDefault("CURRENT", List.of()),
        perFigure.getOrDefault("REPLACEMENT", List.of()));
  }

  private String labelOfPlace(UUID place) {
    // A place that vanished between the sum and the label answers null. The row
    // still says something true about money, and dropping it would make the
    // totals of the places above it stop adding up.
    String name = places.labelOf(place);
    return name == null ? "?" : name;
  }

  private static Set<UUID> union(Set<UUID> one, Set<UUID> other) {
    Set<UUID> all = new java.util.HashSet<>(one);
    all.addAll(other);
    return all;
  }

  /**
   * Sorts, caps and states what the numbers mean.
   *
   * @param dimension which report this is
   * @param rows the rows, in any order
   * @param limit how many at most
   * @param overlapping whether a thing can be in more than one row
   * @return the report
   */
  private static ValuationSummary report(String dimension, List<ValuationRow> rows, int limit, boolean overlapping) {
    List<ValuationRow> sorted =
        rows.stream()
            .sorted(
                java.util.Comparator.comparingLong(ValuationRow::subtreeCount)
                    .reversed()
                    .thenComparing(ValuationRow::label))
            .limit(Math.clamp(limit, 1, MAX_ROWS))
            .toList();

    Set<String> currencies = new TreeSet<>();
    for (ValuationRow row : sorted) {
      for (Figures figures : List.of(row.own(), row.subtree())) {
        for (List<Money> amounts :
            List.of(figures.purchase(), figures.current(), figures.replacement())) {
          amounts.forEach(amount -> currencies.add(amount.currency().getCurrencyCode()));
        }
      }
    }
    // `converted` is always false and is a FIELD rather than an omission:
    // REQ-LIFE-017 asks the report to state that no conversion took place, and a
    // client can render a statement where it cannot render a missing one.
    return new ValuationSummary(dimension, sorted, List.copyOf(currencies), false, overlapping);
  }
}
