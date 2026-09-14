/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.search.api;

import de.greluc.homeinv.platform.SortOrder;
import de.greluc.homeinv.platform.Page;
import de.greluc.homeinv.platform.QueryFilter;
import de.greluc.homeinv.inventory.api.ItemView;
import java.util.List;
import java.util.UUID;

/**
 * Finding things.
 *
 * <p>At stage 0 this is PostgreSQL full text over the two generated vectors on {@code item}
 * (ADR-0047, REQ-SRCH-001). Stage 1 adds OpenSearch as the primary index and keeps this one as the
 * fallback, which is why the block publishes a service rather than the query itself: the caller asks
 * a question and does not learn which engine answered it.
 */
public interface SearchService {

  /**
   * Runs a search.
   *
   * @param request what to look for and where to resume
   * @return the matching items and a cursor for the next page
   */
  Page<ItemView> query(SearchRequest request);

  /**
   * What to search for.
   *
   * @param text the query text; blank lists everything
   * @param language {@code de} or {@code en}, deciding which generated vector is searched
   * @param locationIds restricts the result to items in these locations; {@code null} or empty
   *     means the whole tenant. The cursor is bound to this list as well as to the text, so a
   *     cursor from one location's page is refused on another's (REQ-SRCH-009)
   * @param cursor an opaque cursor from a previous result, or {@code null} for the first page
   * @param filters conditions on attributes, all of which must hold. Refused when a field is not one
   *     this tenant marked searchable, and refused when a range filter over a dimensioned field
   *     names no unit
   * @param facets the dimensions to count beside the rows, or {@code null} for none. {@code type},
   *     {@code category}, {@code tag}, {@code location} and {@code attr.<key>} for a field this
   *     tenant marked facetable; anything else is refused rather than left out of the answer
   * @param sort the order to return them in, or {@code null} for the default. Refused when the
   *     field is not one this tenant marked sortable — the allowlist of
   *     {@code TypeRegistry.queryableFields}, never what a caller typed
   * @param limit how many rows at most
   */
  record SearchRequest(
      String text,
      String language,
      List<UUID> locationIds,
      String cursor,
      SortOrder sort,
      List<QueryFilter> filters,
      List<String> facets,
      int limit) {}

}
