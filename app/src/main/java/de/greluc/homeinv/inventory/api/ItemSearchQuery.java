/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.api;

import de.greluc.homeinv.platform.SortOrder;
import de.greluc.homeinv.platform.CursorCodec;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The full-text query {@code search} may ask of {@code inventory}.
 *
 * <p>A published port rather than a query in {@code search} against {@code inventory.item}: no block
 * touches another block's schema (REQ-NFR-021). The data belongs to {@code inventory} and so does
 * the statement that reads it; {@code search} owns the question, not the table.
 */
public interface ItemSearchQuery {

  /**
   * Returns one page of items matching a full-text query.
   *
   * @param text what to search for; blank means "everything", which is how an unfiltered list is
   *     paged through the same path rather than through a second one
   * @param language which of the two generated vectors to search, {@code de} or {@code en}
   * @param locationIds restricts the result to items in these locations; empty means no
   *     restriction. It is a list rather than a single id because "including the subtree"
   *     (REQ-CORE-049) resolves to a set of locations in the {@code locations} block, and
   *     {@code inventory} must not read that block's schema to work it out for itself
   * @param filters conditions on attributes, all of which must hold. Each becomes an {@code exists}
   *     over {@code item_attr_index} rather than a join, so two filters cannot multiply the rows
   *     between them. Field keys have already been checked against the tenant's allowlist
   * @param sort the order to return them in, or {@code null} for the default. The field has already
   *     been checked against the tenant's allowlist by the caller; this block resolves it to a
   *     column and never to anything a caller typed
   * @param after where to resume, or empty for the first page
   * @param limit how many rows at most
   * @return the page, ordered by creation
   */
  Rows search(
      String text,
      String language,
      List<UUID> locationIds,
      Optional<CursorCodec.Position> after,
      de.greluc.homeinv.platform.SortOrder sort,
      List<de.greluc.homeinv.platform.QueryFilter> filters,
      int limit);

  /**
   * What one search produced.
   *
   * <p><b>Identifiers, not rows.</b> The caller loads them through {@code ItemService.byIds}, which
   * is the same path every other read takes and therefore the same row-level security and the same
   * field visibility (REQ-SRCH-007). The OpenSearch adapter has no choice — a derived index must
   * not hand out content — and this one follows the same contract deliberately, decided with the
   * owner on 2026-09-14: two adapters behind one port are only interchangeable if they answer the
   * same question, and REQ-SRCH-005 is the requirement that they are.
   *
   * <p>The price is a second query on the fallback path, which the {@code minimal} profile pays on
   * every search. It is a lookup by primary key on rows the first query has just touched.
   *
   * <p>Deliberately not the response envelope {@code platform.Page}: this carries a cursor
   * <em>position</em> rather than a signed cursor string, because this block does not know how
   * cursors are signed and should not.
   *
   * @param ids the matching items, in the order they are to be shown
   * @param last the position of the final row, or empty when there were none
   */
  record Rows(List<UUID> ids, Optional<CursorCodec.Position> last) {}
}
