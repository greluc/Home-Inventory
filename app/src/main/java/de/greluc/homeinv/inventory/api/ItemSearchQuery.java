/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.api;

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
   * @param after where to resume, or empty for the first page
   * @param limit how many rows at most
   * @return the page, ordered by creation
   */
  Page search(
      String text,
      String language,
      List<UUID> locationIds,
      Optional<CursorCodec.Position> after,
      int limit);

  /**
   * One page of results.
   *
   * @param items the rows, in order
   * @param last the position of the final row, or empty when the page is empty. The caller turns it
   *     into a cursor; this block does not know how cursors are signed and should not
   */
  record Page(List<ItemView> items, Optional<CursorCodec.Position> last) {}
}
