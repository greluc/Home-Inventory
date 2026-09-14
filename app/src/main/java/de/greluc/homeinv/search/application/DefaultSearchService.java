/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.search.application;

import de.greluc.homeinv.platform.SortOrder;
import de.greluc.homeinv.platform.Page;
import de.greluc.homeinv.search.api.SearchIndex;
import de.greluc.homeinv.platform.CursorCodec;
import de.greluc.homeinv.platform.QueryFilter;
import de.greluc.homeinv.search.api.SearchService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Search, answered at stage 0 by PostgreSQL full text.
 *
 * <p>The block owns the question and not the table: the rows come from {@code inventory} through its
 * published {@link ItemSearchQuery} port, because no block reads another block's schema
 * (REQ-NFR-021). When OpenSearch arrives at stage 1 it becomes a second implementation behind the
 * same service, and callers do not change.
 */
@Service
@RequiredArgsConstructor
public class DefaultSearchService implements SearchService {

  /** More than this per page and the response stops being a page (REQ-SEC-065's spirit). */
  private static final int MAX_LIMIT = 200;

  private static final int DEFAULT_LIMIT = 50;

  /** The facet dimensions that are not an attribute. An attribute is checked against the tenant. */
  private static final java.util.Set<String> COUNTABLE =
      java.util.Set.of("type", "category", "tag", "location");

  /**
   * Where the search is actually run (REQ-SRCH-005).
   *
   * <p>One today. When OpenSearch joins it, this becomes the ordered list of engines to try and the
   * fallback becomes a decision made here rather than in an adapter — which is why the service asks
   * a port and not {@code ItemSearchQuery} directly, even while there is only one of them.
   */
  private final SearchIndex index;

  /** Turns the ids the index answered with back into rows (REQ-SRCH-007). */
  private final de.greluc.homeinv.inventory.api.ItemService items;

  /**
   * The allowlist an ordering is checked against (REQ-SRCH-004).
   *
   * <p>Checked here rather than in the adapter, because "may this be sorted by" is a decision and an
   * adapter decides nothing (ADR-0010). It is also the only place that can refuse before an engine
   * is chosen, so the answer does not depend on which one answered.
   */
  private final de.greluc.homeinv.catalog.api.TypeRegistry types;

  /**
   * Where a tag filter is answered (REQ-SRCH-002).
   *
   * <p>{@code tagging} owns the assignment and answers with item ids, so no other block joins its
   * tables and no engine has to know what a tag is (ADR-0002).
   */
  private final de.greluc.homeinv.tagging.api.TagQueries tags;

  /**
   * Where {@code location:subtree:} is answered.
   *
   * <p>The tree is {@code locations}' and the walk is its {@code ltree}'s. What comes back is a set
   * of ids, which is what {@code inventory} already narrows by.
   */
  private final de.greluc.homeinv.locations.api.LocationService locations;

  private final CursorCodec cursors;

  @Override
  @Transactional(readOnly = true)
  public Page<de.greluc.homeinv.inventory.api.ItemView> query(SearchRequest request) {
    int limit = request.limit() <= 0 ? DEFAULT_LIMIT : Math.min(request.limit(), MAX_LIMIT);
    String language = request.language() == null ? "de" : request.language();
    String text = request.text() == null ? "" : request.text();

    List<UUID> locationIds =
        request.locationIds() == null ? List.of() : List.copyOf(request.locationIds());

    String fingerprint =
        fingerprintOf(text, language, locationIds, request.sort(), request.filters());
    Optional<CursorCodec.Position> after =
        request.cursor() == null || request.cursor().isBlank()
            ? Optional.empty()
            // Throws when the cursor was tampered with or belongs to another
            // query. Rejecting is the point: an unverified cursor silently
            // resumes somewhere else (REQ-SEC-106, REQ-SRCH-009).
            : Optional.of(cursors.decode(request.cursor(), fingerprint));

    SortOrder sort = allowed(request.sort());

    // Attributes are the only dimension an engine answers itself. Every other one
    // belongs to another block, is resolved here through that block's port, and
    // reaches the engine as a set of ids.
    List<QueryFilter> all = request.filters() == null ? List.of() : request.filters();
    List<QueryFilter> filters =
        allowed(all.stream().filter(one -> one.dimension() == QueryFilter.Dimension.ATTRIBUTE)
            .toList());

    Scope scope = resolve(all, locationIds);

    Page<de.greluc.homeinv.inventory.api.ItemView> page;
    if (scope.empty()) {
      // A type nobody uses, a tag nothing carries, a subtree that is empty: the
      // answer is no rows, and asking an engine for the rows in an empty set of
      // ids would be a query whose answer is already known. The facets below are
      // still counted, because each drops its own filter and the sidebar is how
      // somebody gets out of a combination that matched nothing.
      page = Page.of(List.of(), null);
    } else {
      SearchIndex.Hits hits =
          index.find(
              new SearchIndex.Query(
                  text,
                  language,
                  scope.locationIds(),
                  scope.typeVersionIds(),
                  scope.itemIds(),
                  after,
                  sort,
                  filters,
                  limit));

      // The ids become rows here, through the ordinary read: row-level security
      // and the field visibility rules apply on the way out, which is what makes
      // a derived index safe to run at all (REQ-SRCH-007). An id that named a row
      // this tenant cannot see is simply absent, so a stale index costs a shorter
      // page and never somebody else's item.
      List<de.greluc.homeinv.inventory.api.ItemView> rows = items.byIds(hits.itemIds());

      String nextCursor =
          hits.last().map(position -> cursors.encode(position, fingerprint)).orElse(null);
      page = Page.of(rows, nextCursor);
    }

    List<String> dimensions = counted(request.facets());
    return dimensions.isEmpty()
        ? page
        : page.withFacets(facetsOf(dimensions, all, locationIds, text, language, limit));
  }

  /**
   * Checks the dimensions a caller asked to have counted (REQ-SRCH-002).
   *
   * <p>Four are fixed and the fifth is an attribute, which has to be one some published type marks
   * {@code facetable} — the tenant's own allowlist, never what a caller typed. A dimension that
   * is not countable is refused rather than left out of the answer, for the same reason an unknown
   * filter is: a sidebar silently missing a section looks like a sidebar with nothing in it.
   *
   * @param asked what the caller named, possibly none
   * @return the dimensions to count, in the order they were asked for and without repeats
   * @throws IllegalArgumentException when a dimension cannot be counted
   */
  private List<String> counted(List<String> asked) {
    if (asked == null || asked.isEmpty()) {
      return List.of();
    }
    var queryable = types.queryableFields();
    List<String> dimensions = new ArrayList<>();
    for (String dimension : asked) {
      if (dimension == null || dimension.isBlank()) {
        throw new IllegalArgumentException("An empty facet counts nothing");
      }
      String wanted = dimension.trim();
      if (dimensions.contains(wanted)) {
        continue;
      }
      if (wanted.startsWith(SortOrder.ATTRIBUTE_PREFIX)) {
        String key = wanted.substring(SortOrder.ATTRIBUTE_PREFIX.length());
        boolean facetable =
            queryable.stream().anyMatch(field -> field.key().equals(key) && field.facetable());
        if (!facetable) {
          throw new IllegalArgumentException(
              "Cannot count by " + wanted + "; no published type marks it facetable");
        }
      } else if (!COUNTABLE.contains(wanted)) {
        throw new IllegalArgumentException(
            "Not a facet dimension: "
                + wanted
                + "; expected type, category, tag, location or attr.<key>");
      }
      dimensions.add(wanted);
    }
    return dimensions;
  }

  /**
   * Counts each dimension, every one over a query of its own (REQ-SRCH-002).
   *
   * <p><b>Without its own filter.</b> With {@code filter=tag:broken} active the tag facet still
   * counts every tag, because a sidebar exists to show where one could click next and a count of
   * the thing already clicked is not that (decided with the owner 2026-09-14). Every other
   * dimension's filters do narrow it, so the numbers still describe the list on the screen.
   *
   * <p><b>Except a location</b>, which keeps its filter and uses it to set the level instead. A
   * tree is drilled into by descending: inside the shed one wants the shed's shelves, not the
   * neighbouring rooms.
   *
   * @param dimensions what to count, already checked
   * @param all every filter the caller gave
   * @param scopedLocations the locations the caller is confined to, or empty
   * @param text the query text
   * @param language which vector to search
   * @param limit the page size, which the engine needs for a well-formed query and ignores here
   * @return one facet per dimension, in the order asked for
   */
  private List<de.greluc.homeinv.platform.Facet> facetsOf(
      List<String> dimensions,
      List<QueryFilter> all,
      List<UUID> scopedLocations,
      String text,
      String language,
      int limit) {
    List<de.greluc.homeinv.platform.Facet> counted = new ArrayList<>();
    for (String dimension : dimensions) {
      boolean tree = "location".equals(dimension);
      List<QueryFilter> others =
          all.stream().filter(one -> tree || !narrows(one, dimension)).toList();
      Scope scope = resolve(others, scopedLocations);
      if (scope.empty()) {
        counted.add(new de.greluc.homeinv.platform.Facet(dimension, List.of()));
        continue;
      }
      counted.add(
          index.facet(
              new SearchIndex.Query(
                  text,
                  language,
                  scope.locationIds(),
                  scope.typeVersionIds(),
                  scope.itemIds(),
                  Optional.empty(),
                  null,
                  allowed(
                      others.stream()
                          .filter(one -> one.dimension() == QueryFilter.Dimension.ATTRIBUTE)
                          .toList()),
                  limit),
              dimension,
              tree ? treeRootOf(all) : null));
    }
    return counted;
  }

  /**
   * Whether one filter narrows the dimension being counted.
   *
   * @param filter the filter
   * @param dimension the dimension being counted
   * @return true when dropping this filter is what the drill-down rule asks for
   */
  private static boolean narrows(QueryFilter filter, String dimension) {
    return dimension.startsWith(SortOrder.ATTRIBUTE_PREFIX)
        ? filter.dimension() == QueryFilter.Dimension.ATTRIBUTE
            && dimension.equals(SortOrder.ATTRIBUTE_PREFIX + filter.field())
        : filter.dimension().token().equals(dimension);
  }

  /**
   * The place a location facet counts the children of.
   *
   * <p>The deepest subtree somebody asked for, because that is where they are looking. Without a
   * subtree filter there is no root and the facet counts the roots of the tree.
   *
   * @param all every filter the caller gave
   * @return the subtree root, or {@code null}
   */
  private static UUID treeRootOf(List<QueryFilter> all) {
    UUID root = null;
    for (QueryFilter filter : all) {
      if (filter.dimension() == QueryFilter.Dimension.LOCATION
          && filter.operator() == QueryFilter.Operator.SUBTREE) {
        root = UUID.fromString(filter.values().get(filter.values().size() - 1).trim());
      }
    }
    return root;
  }

  /**
   * A stable fingerprint of the query a cursor belongs to.
   *
   * <p>Hashed rather than carried in clear, so the cursor does not grow with the query and does not
   * echo the search text back through a URL that may end up in a log or a referrer header.
   *
   * @param text the query text
   * @param language the language
   * @param locationIds the location filter, sorted into the digest so that the same set in a
   *     different order is recognised as the same query
   * @return a hex digest identifying this query
   */
  private static String fingerprintOf(
      String text,
      String language,
      List<UUID> locationIds,
      SortOrder sort,
      List<QueryFilter> filters) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      // The separator matters: without it, ("ab", "c") and ("a", "bc") would
      // hash alike, and a cursor from one search would be accepted by the other.
      // The location filter is part of the query, so it is part of what the
      // cursor is bound to. Without it a cursor from "everything in the cellar"
      // would resume a search of the whole tenant at the same row.
      String filter =
          locationIds.stream().map(UUID::toString).sorted().collect(Collectors.joining(","));
      byte[] hash =
          digest.digest(
              // The order is part of the query too. Resuming a list sorted by name
              // with a cursor taken from one sorted by date would page through a
              // sequence that never existed, which is what REQ-SRCH-009 refuses.
              (text
                      + " "
                      + language
                      + " "
                      + filter
                      + " "
                      + (sort == null ? "" : sort.field() + (sort.descending() ? " desc" : " asc"))
                      + " "
                      // Every filter, in a stable order. REQ-SRCH-009's acceptance is
                      // "a cursor with changed filters is rejected", and a cursor that
                      // survived a filter change would resume a list that no longer
                      // exists at a position taken from one that did.
                      + (filters == null
                          ? ""
                          : filters.stream()
                              .map(
                                  one ->
                                      one.dimension().token()
                                          + ":"
                                          + (one.field() == null ? "" : one.field())
                                          + ":"
                                          + one.operator().token()
                                          + ":"
                                          + String.join(",", one.values())
                                          + ":"
                                          + (one.unit() == null ? "" : one.unit()))
                              .sorted()
                              .collect(Collectors.joining("&"))))
                  .getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash, 0, 16);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  /** What may be ordered by besides the tenant's own fields. A closed set, not a convention. */
  private static final java.util.Set<String> SORTABLE_COLUMNS =
      java.util.Set.of("name", "createdAt", "updatedAt");

  /**
   * Refuses an ordering the tenant did not allow (REQ-SRCH-004).
   *
   * <p>The allowlist CLAUDE.md requires: a sort name reaching SQL comes from {@code
   * field_definition} or from the closed set above, and never from what a caller typed. An unknown
   * field, or one the tenant has not marked sortable, is refused rather than ignored — a list
   * silently returned in the wrong order is a list somebody will read as though it were right.
   *
   * @param sort what was asked for, or {@code null} for the default order
   * @return the same ordering when it is allowed, or {@code null} when none was asked for
   * @throws IllegalArgumentException when the field may not be ordered by
   */
  private SortOrder allowed(SortOrder sort) {
    if (sort == null) {
      return null;
    }
    if (!sort.isAttribute()) {
      if (!SORTABLE_COLUMNS.contains(sort.field())) {
        throw new IllegalArgumentException(
            "Cannot sort by " + sort.field() + "; it is not a field of an item");
      }
      return sort;
    }
    String key = sort.attributeKey();
    boolean sortable =
        types.queryableFields().stream()
            .anyMatch(field -> field.key().equals(key) && field.sortable());
    if (!sortable) {
      // Named rather than hidden: the tenant configures which fields are
      // sortable, so somebody can act on this — either by marking the field or
      // by correcting the request.
      throw new IllegalArgumentException(
          "Cannot sort by " + sort.field() + "; no published type marks it sortable");
    }
    return sort;
  }

  /**
   * What the scope filters narrow to, as sets of ids.
   *
   * @param locationIds the locations, or empty for no restriction
   * @param typeVersionIds the item-type versions, or empty for no restriction
   * @param itemIds the items, or empty for no restriction
   * @param empty whether some filter resolved to nothing at all, which means no row can match
   */
  private record Scope(
      List<UUID> locationIds, List<UUID> typeVersionIds, List<UUID> itemIds, boolean empty) {}

  /**
   * Resolves every filter that is not an attribute, through the block that owns it (REQ-SRCH-002).
   *
   * <p>Three dimensions and three owners: {@code type} is a key {@code catalog} translates,
   * {@code tag} is an assignment {@code tagging} answers with item ids, and
   * {@code location:subtree:} is a walk of {@code locations}' tree. Nothing here reads another
   * block's tables, which is the whole reason a filter carries a dimension rather than a column.
   *
   * <p>Several filters on one dimension <b>intersect</b>, because every filter given has to hold —
   * {@code tag:broken} and {@code tag:heavy} together mean both, while {@code tag:in:broken,heavy}
   * means either. That is the distinction that makes a multi-select facet behave the way people
   * expect.
   *
   * <p>A value nothing matches resolves to the empty set, and the whole query is then known to have
   * no answer. That is reported rather than refused: a tag nobody has used matches no item, which
   * is a true answer to the question and not a malformed request.
   *
   * @param filters every filter the caller gave, attributes included
   * @param scopedLocations the locations the caller was already confined to, or empty
   * @return the resolved sets
   * @throws IllegalArgumentException when a location filter does not name a uuid
   */
  private Scope resolve(List<QueryFilter> filters, List<UUID> scopedLocations) {
    List<UUID> locationIds = scopedLocations;
    List<UUID> typeVersionIds = List.of();
    List<UUID> itemIds = List.of();
    boolean empty = false;

    for (QueryFilter filter : filters) {
      switch (filter.dimension()) {
        case ATTRIBUTE -> {
          // Answered by the engine, not here.
        }
        case TYPE -> {
          List<UUID> resolved = types.itemTypeVersionsByKeys(filter.values());
          empty |= resolved.isEmpty();
          typeVersionIds =
              typeVersionIds.isEmpty() ? resolved : intersect(typeVersionIds, resolved);
          empty |= typeVersionIds.isEmpty();
        }
        case TAG -> {
          List<UUID> resolved = tags.itemsTagged(filter.values());
          empty |= resolved.isEmpty();
          itemIds = itemIds.isEmpty() ? resolved : intersect(itemIds, resolved);
          empty |= itemIds.isEmpty();
        }
        case LOCATION -> {
          List<UUID> resolved = placesOf(filter);
          empty |= resolved.isEmpty();
          locationIds = locationIds.isEmpty() ? resolved : intersect(locationIds, resolved);
          empty |= locationIds.isEmpty();
        }
      }
    }
    return new Scope(locationIds, typeVersionIds, itemIds, empty);
  }

  /**
   * The locations one {@code location} filter names.
   *
   * @param filter the filter, with {@code subtree} or without it
   * @return the location ids, the subtree included where it was asked for
   * @throws IllegalArgumentException when a value is not a uuid
   */
  private List<UUID> placesOf(QueryFilter filter) {
    List<UUID> named = new ArrayList<>();
    for (String value : filter.values()) {
      try {
        named.add(UUID.fromString(value.trim()));
      } catch (IllegalArgumentException notAnId) {
        throw new IllegalArgumentException(
            "A location filter names a location by id, not " + value);
      }
    }
    if (filter.operator() != QueryFilter.Operator.SUBTREE) {
      return named;
    }
    // `subtreeIds` includes the node itself, and a location this tenant cannot
    // see raises the ordinary not-found rather than quietly widening the answer.
    List<UUID> withDescendants = new ArrayList<>();
    for (UUID place : named) {
      withDescendants.addAll(locations.subtreeIds(place));
    }
    return withDescendants;
  }

  /**
   * The ids both sets hold.
   *
   * @param first one set
   * @param second the other
   * @return their intersection, in the order of the first
   */
  private static List<UUID> intersect(List<UUID> first, List<UUID> second) {
    var keep = new java.util.HashSet<>(second);
    return first.stream().filter(keep::contains).toList();
  }

  /**
   * Refuses a filter the tenant did not allow, or one that cannot mean anything (REQ-SRCH-003).
   *
   * <p>Two checks, and the second is the one that is easy to leave out. The field has to be one some
   * published type marks searchable — the allowlist, never what a caller typed. And a <b>range</b>
   * over a field that carries a unit has to name that unit: {@code item_attr_index} keeps
   * {@code unit_value} beside {@code num_value} so that a total never adds euros to dollars, and a
   * comparison is a subtraction with the sign thrown away. "Over 100" across currencies is the
   * plausible wrong answer that the column exists to prevent.
   *
   * <p>Equality needs no unit. "Is it exactly 100" is answerable across currencies, because no
   * amount of euros equals an amount of dollars either.
   *
   * @param filters the attribute conditions asked for, possibly none. Every other dimension has
   *     already been resolved to ids and is not the allowlist's business
   * @return the same filters when every one of them is allowed
   * @throws IllegalArgumentException when a field may not be filtered, or a range over a
   *     dimensioned field names no unit
   */
  private List<QueryFilter> allowed(
      List<QueryFilter> filters) {
    if (filters == null || filters.isEmpty()) {
      return List.of();
    }
    var queryable = types.queryableFields();
    for (QueryFilter filter : filters) {
      var field =
          queryable.stream()
              .filter(known -> known.key().equals(filter.field()) && known.filterable())
              .findFirst()
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "Cannot filter by "
                              + filter.field()
                              + "; no published type marks it searchable"));
      if (filter.isRange() && field.dataType().carriesUnit() && filter.unit() == null) {
        throw new IllegalArgumentException(
            "A range over "
                + filter.field()
                + " needs a unit: it holds a "
                + field.dataType().token()
                + ", and comparing across units is meaningless");
      }
      if (!field.dataType().carriesUnit() && filter.unit() != null) {
        throw new IllegalArgumentException(
            filter.field() + " carries no unit, so a filter on it must not name one");
      }
    }
    return filters;
  }
}
