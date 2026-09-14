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

    List<QueryFilter> filters = allowed(request.filters());

    SearchIndex.Hits hits =
        index.find(
            new SearchIndex.Query(text, language, locationIds, after, sort, filters, limit));

    // The ids become rows here, through the ordinary read: row-level security and
    // the field visibility rules apply on the way out, which is what makes a
    // derived index safe to run at all (REQ-SRCH-007). An id that named a row
    // this tenant cannot see is simply absent, so a stale index costs a shorter
    // page and never somebody else's item.
    List<de.greluc.homeinv.inventory.api.ItemView> rows = items.byIds(hits.itemIds());

    String nextCursor =
        hits.last().map(position -> cursors.encode(position, fingerprint)).orElse(null);
    return Page.of(rows, nextCursor);
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
                                      one.field()
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
   * @param filters what was asked for, possibly none
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
