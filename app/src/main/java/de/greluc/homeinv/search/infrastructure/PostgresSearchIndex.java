/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.search.infrastructure;

import de.greluc.homeinv.catalog.api.TypeRegistry;
import de.greluc.homeinv.inventory.api.ItemSearchQuery;
import de.greluc.homeinv.locations.api.LocationService;
import de.greluc.homeinv.platform.Facet;
import de.greluc.homeinv.platform.SortOrder;
import de.greluc.homeinv.search.api.SearchIndex;
import de.greluc.homeinv.tagging.api.TagQueries;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * PostgreSQL behind the {@link SearchIndex} port (ADR-0008, REQ-SRCH-005).
 *
 * <p>The shipped fallback, and the only search the {@code minimal} profile has — which is why it is
 * not a stub. Its full-text matching is the two generated {@code tsvector} columns on
 * {@code inventory.item}, one per shipped language (ADR-0047), and its paging is keyset rather than
 * offset (REQ-SRCH-009).
 *
 * <h2>It is thin on purpose</h2>
 *
 * <p>The statement itself lives in {@code inventory}, behind {@code ItemSearchQuery}: no block reads
 * another block's schema (REQ-NFR-021), and the table belongs to {@code inventory} whoever is
 * asking. What this class adds is the shape of the port — which is almost nothing, and that is the
 * point. When the OpenSearch adapter arrives beside it, the difference between the two will be the
 * engine and not the contract.
 *
 * <h2>Always available</h2>
 *
 * <p>{@link #available()} is {@code true} without asking. The database is where the items are: if it
 * is unreachable the request has already failed for reasons no search fallback could mend, and a
 * health check here would be a query asking whether queries work.
 *
 * <h2>Facets are composed, not joined</h2>
 *
 * <p>{@link #facet} is the one method here with work of its own, and the work is composition rather
 * than SQL. {@code inventory} counts the rows it owns, grouped by a column it owns; {@code catalog}
 * says which type a version belongs to and what that type sits under; {@code tagging} counts tags
 * over items it is handed; {@code locations} rolls counts up its own tree. Each half is a published
 * port, so a facet crosses no schema boundary (ADR-0002) at the cost of one extra query per
 * dimension — which is the "fewer facets, but correct" 03 §3 promises of this engine, and
 * exactly what an OpenSearch document does in one aggregation because it was denormalised for it.
 */
@Component
@RequiredArgsConstructor
public class PostgresSearchIndex implements SearchIndex {

  private final ItemSearchQuery items;

  /** Turns a count per type version into the {@code type} and {@code category} facets. */
  private final TypeRegistry types;

  /** Counts tags over the items this query matched. */
  private final TagQueries tags;

  /** Rolls a count per location up to the children of the place being looked at. */
  private final LocationService locations;

  /**
   * What this engine is called.
   *
   * @return {@code postgresql}
   */
  @Override
  public String name() {
    return "postgresql";
  }

  /**
   * Whether this index can answer, which it always can.
   *
   * @return {@code true}
   */
  @Override
  public boolean available() {
    return true;
  }

  /**
   * Finds the matching items, oldest first.
   *
   * <p>Relevance ranking is OpenSearch's advantage and this adapter does not pretend to it: the
   * order is the keyset order, which is stable and resumable. `degraded-reasons.yaml` says so in as
   * many words — under the fallback "the ranking is poorer", and the hits are still correct.
   *
   * @param query what to look for
   * @return the ids and where the next page resumes
   */
  @Override
  public Hits find(Query query) {
    ItemSearchQuery.Rows rows =
        items.search(criteriaOf(query), query.after(), query.sort(), query.limit());
    return new Hits(rows.ids(), rows.last());
  }

  /**
   * Does nothing, because this engine's index is the database.
   *
   * <p>Not a stub. The full-text match reads the two generated {@code tsvector} columns on
   * {@code inventory.item} and the filters read {@code item_attr_index}, and both are written in
   * the same transaction as the item (REQ-CORE-013). There is no second copy to bring up to date,
   * which is also why this engine never reports {@code index-stale}.
   *
   * @param document ignored
   */
  @Override
  public void index(de.greluc.homeinv.search.api.SearchDocument document) {
    // Intentionally empty; see the Javadoc.
  }

  /**
   * Does nothing, for the same reason as {@link #index}.
   *
   * @param tenantId ignored
   * @param itemId ignored
   */
  @Override
  public void remove(UUID tenantId, UUID itemId) {
    // Intentionally empty; trashing an item already clears its projection in the
    // same transaction (07 §7.3).
  }

  @Override
  public Facet facet(Query query, String dimension, UUID locationRoot) {
    ItemSearchQuery.Criteria criteria = criteriaOf(query);
    Map<String, Long> counts =
        switch (dimension) {
          case "type" -> byType(criteria, false);
          case "category" -> byType(criteria, true);
          case "tag" -> tags.countByTag(items.matchingIds(criteria));
          case "location" -> byLocation(criteria, locationRoot);
          default -> {
            if (!dimension.startsWith(SortOrder.ATTRIBUTE_PREFIX)) {
              throw new IllegalArgumentException(
                  "Not a facet dimension: "
                      + dimension
                      + "; expected type, category, tag, location or attr.<key>");
            }
            yield items.countByAttribute(
                criteria, dimension.substring(SortOrder.ATTRIBUTE_PREFIX.length()));
          }
        };
    return new Facet(dimension, bucketsOf(counts));
  }

  /**
   * The {@code type} or {@code category} facet, from one count per type version.
   *
   * <p>Several versions of one type collapse into one bucket, and several types under one parent
   * collapse likewise: an item written against version 2 and an item written against version 5 are
   * both power tools, and a facet that listed the versions would be counting the type editor's
   * history rather than the inventory.
   *
   * @param criteria what narrows the query
   * @param byParent true for {@code category}, which counts the type above each type
   * @return the count per type key, or per parent key
   */
  private Map<String, Long> byType(ItemSearchQuery.Criteria criteria, boolean byParent) {
    Map<UUID, Long> perVersion =
        items.countByColumn(criteria, ItemSearchQuery.CountColumn.TYPE_VERSION);
    Map<UUID, TypeRegistry.TypeIdentity> identities = types.typesOfVersions(perVersion.keySet());

    Map<String, Long> counts = new LinkedHashMap<>();
    perVersion.forEach(
        (version, count) -> {
          TypeRegistry.TypeIdentity identity = identities.get(version);
          if (identity == null) {
            return;
          }
          // A type at the root of the tree is in no category, so it contributes
          // no bucket rather than a bucket called "none" that nothing filters by.
          String key = byParent ? identity.parentKey() : identity.key();
          if (key != null) {
            counts.merge(key, count, Long::sum);
          }
        });
    return counts;
  }

  /**
   * The {@code location} facet, rolled up to the children of one place.
   *
   * @param criteria what narrows the query
   * @param root the place whose children are counted, or {@code null} for the roots of the tree
   * @return the count per child location, keyed by id because that is what a filter takes
   */
  private Map<String, Long> byLocation(ItemSearchQuery.Criteria criteria, UUID root) {
    Map<UUID, Long> perPlace =
        items.countByColumn(criteria, ItemSearchQuery.CountColumn.LOCATION);
    Map<String, Long> counts = new LinkedHashMap<>();
    locations
        .rollUp(perPlace, root)
        .forEach((place, count) -> counts.put(place.toString(), count));
    return counts;
  }

  /**
   * Orders the counts and caps them.
   *
   * <p>Largest first, and ties broken by the value so that two requests with the same data answer
   * in the same order — an unstable order would make a sidebar jump about between clicks.
   *
   * @param counts the count per value
   * @return at most {@link Facet#MAX_BUCKETS} buckets
   */
  private static List<Facet.Bucket> bucketsOf(Map<String, Long> counts) {
    return counts.entrySet().stream()
        .map(entry -> new Facet.Bucket(entry.getKey(), entry.getValue()))
        .sorted(
            Comparator.comparingLong(Facet.Bucket::count)
                .reversed()
                .thenComparing(Facet.Bucket::value))
        .limit(Facet.MAX_BUCKETS)
        .toList();
  }

  /**
   * The part of a query that narrows it, in the shape {@code inventory} takes.
   *
   * @param query the engine's query
   * @return the criteria
   */
  private static ItemSearchQuery.Criteria criteriaOf(Query query) {
    return new ItemSearchQuery.Criteria(
        query.text(),
        query.language(),
        query.locationIds(),
        query.typeVersionIds(),
        query.itemIds(),
        query.filters());
  }
}
