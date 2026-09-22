/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.graphql;

import de.greluc.homeinv.authorization.api.Permission;
import de.greluc.homeinv.authorization.api.RequiresPermission;
import de.greluc.homeinv.inventory.api.ItemView;
import de.greluc.homeinv.platform.Facet;
import de.greluc.homeinv.platform.NotFoundException;
import de.greluc.homeinv.platform.Page;
import de.greluc.homeinv.platform.QueryFilter;
import de.greluc.homeinv.platform.SortOrder;
import de.greluc.homeinv.search.api.SavedSearches;
import de.greluc.homeinv.search.api.SearchService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

/**
 * Saved searches and counts (REQ-API-006, REQ-SRCH-002, REQ-SRCH-008).
 *
 * <p>The two queries a reporting client actually makes, and neither of them is a list of rows: a
 * saved search is a question somebody stored, and {@code stats} is the answer to "how many of each"
 * without the rows that make it up.
 */
@Controller
@RequiredArgsConstructor
public class SearchQueries {

  private final SavedSearches savedSearches;
  private final SearchService search;

  /**
   * The items a saved search finds, run now.
   *
   * @param id which saved search
   * @param first how many at most
   * @param after an opaque cursor from a previous page
   * @return the search and its current results, or {@code null} when this tenant has no such search
   */
  @QueryMapping
  @RequiresPermission(Permission.SEARCH_QUERY)
  public SavedSearchResult savedSearch(
      @Argument UUID id, @Argument Integer first, @Argument String after) {
    SavedSearches.SavedSearchView saved;
    try {
      saved = savedSearches.get(id);
    } catch (NotFoundException absent) {
      return null;
    }
    Page<ItemView> found = savedSearches.run(id, after, ItemQueries.bounded(first));
    return new SavedSearchResult(saved.id(), saved.name(), ItemQueries.Connection.of(found));
  }

  /**
   * How many items fall into each value of one dimension.
   *
   * <p>The facet counts the search already computes, asked for on their own. A report wants the
   * counts and not the rows, so one row is fetched rather than fifty — the count is computed over
   * the whole matching set either way, which is what makes a facet a facet (REQ-SRCH-002).
   *
   * @param groupBy which dimension
   * @param q free text, or {@code null}
   * @param language which analyser the text is searched with
   * @param filter the filter grammar of 08 §8.2
   * @return one bucket per value, empty when nothing matches
   */
  @QueryMapping
  @RequiresPermission(Permission.SEARCH_QUERY)
  public List<StatsBucket> stats(
      @Argument String groupBy,
      @Argument String q,
      @Argument String language,
      @Argument List<String> filter) {
    // Mapped rather than lower-cased: `groupBy` is a GraphQL enum, so the set is
    // closed, and a `switch` says what each value means instead of relying on the
    // two names happening to match after a case fold — which find-sec-bugs refuses
    // anyway, because folding maps characters outside ASCII onto ASCII ones.
    String dimension =
        switch (groupBy) {
          case "TYPE" -> "type";
          case "TAG" -> "tag";
          case "LOCATION" -> "location";
          default ->
              throw new IllegalArgumentException(
                  "There is no dimension called " + groupBy + ". The schema lists the three.");
        };
    Page<ItemView> answered =
        search.query(
            new SearchService.SearchRequest(
                q,
                language == null ? "de" : language,
                List.of(),
                null,
                SortOrder.parse(null),
                filter == null ? List.of() : filter.stream().map(QueryFilter::parse).toList(),
                List.of(dimension),
                1));

    List<Facet> facets = answered.facets();
    if (facets == null) {
      return List.of();
    }
    return facets.stream()
        .filter(facet -> facet.dimension().equals(dimension))
        .flatMap(facet -> facet.buckets().stream())
        .map(bucket -> new StatsBucket(bucket.value(), bucket.count()))
        .toList();
  }

  /**
   * A saved search and what it currently finds.
   *
   * @param id its id
   * @param name what a person called it
   * @param items the items it finds now, paged
   */
  public record SavedSearchResult(UUID id, String name, ItemQueries.Connection<ItemView> items) {}

  /**
   * How many items hold one value of a dimension.
   *
   * @param value the value: a type key, a tag name or a location id
   * @param count how many items hold it
   */
  public record StatsBucket(String value, long count) {}
}
