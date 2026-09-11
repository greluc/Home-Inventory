/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import de.greluc.homeinv.authorization.api.Permission;
import de.greluc.homeinv.authorization.api.RequiresPermission;
import de.greluc.homeinv.search.api.SearchService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The {@code /api/v1/search} endpoint.
 *
 * <p>{@code GET}, because a search is a read and a reader expects to be able to bookmark and share
 * one. That puts the query text in a URL, which is why the cursor carries a <em>hash</em> of the
 * query rather than the query itself — a URL ends up in logs and referrer headers, and one copy of
 * the search text there is enough.
 */
@RestController
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
public class SearchController {

  private final SearchService search;

  /**
   * Searches items by name and description.
   *
   * @param q what to look for; omitted lists everything, paged the same way
   * @param language {@code de} or {@code en}, deciding which generated vector is searched
   * @param cursor an opaque cursor from a previous response, or omitted for the first page
   * @param limit how many rows at most
   * @return the matching items and a cursor for the next page, or none when this was the last
   */
  @GetMapping
  @RequiresPermission(Permission.SEARCH_QUERY)
  public SearchService.SearchResult search(
      @RequestParam(required = false) @Size(max = 500) String q,
      @RequestParam(required = false, defaultValue = "de") @Size(max = 5) String language,
      @RequestParam(required = false) @Size(max = 500) String cursor,
      @RequestParam(required = false, defaultValue = "50") @Positive @Max(200) int limit) {
    return search.query(new SearchService.SearchRequest(q, language, cursor, limit));
  }
}
