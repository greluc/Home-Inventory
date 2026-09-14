/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.api;

import de.greluc.homeinv.platform.CursorCodec;
import de.greluc.homeinv.platform.QueryFilter;
import de.greluc.homeinv.platform.SortOrder;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The queries {@code search} may ask of {@code inventory}.
 *
 * <p>A published port rather than a query in {@code search} against {@code inventory.item}: no block
 * touches another block's schema (REQ-NFR-021). The data belongs to {@code inventory} and so does
 * the statement that reads it; {@code search} owns the question, not the table.
 *
 * <p>Three shapes of question, all over the same {@link Criteria}: which items match and in what
 * order ({@link #search}), how many match per value of a column this block owns
 * ({@link #countByColumn}, {@link #countByAttribute}), and which items match at all
 * ({@link #matchingIds}) — the last for the facets another block has to count, because
 * {@code tagging} can only count tags over items it has been handed.
 */
public interface ItemSearchQuery {

  /**
   * What narrows a query, other than the ordering and the page.
   *
   * <p>One record rather than six parameters repeated on four methods. Every member is already
   * resolved: the ids come from the blocks that own them and the field keys have been checked
   * against the tenant's allowlist, so nothing here is what a caller typed.
   *
   * @param text what to search for; blank means "everything", which is how an unfiltered list is
   *     paged through the same path rather than through a second one
   * @param language which of the two generated vectors to search, {@code de} or {@code en}
   * @param locationIds restricts the result to items in these locations; empty means no
   *     restriction. It is a list rather than a single id because "including the subtree"
   *     (REQ-CORE-049) resolves to a set of locations in the {@code locations} block, and
   *     {@code inventory} must not read that block's schema to work it out for itself
   * @param typeVersionIds the item-type versions to restrict to, or empty for no restriction
   * @param itemIds the items to restrict to, or empty for no restriction. Another block resolved
   *     these — a tag filter, today — and this block narrows by id without knowing what the
   *     question was
   * @param filters conditions on attributes, all of which must hold. Each becomes an {@code exists}
   *     over {@code item_attr_index} rather than a join, so two filters cannot multiply the rows
   *     between them. Field keys have already been checked against the tenant's allowlist
   */
  record Criteria(
      String text,
      String language,
      List<UUID> locationIds,
      List<UUID> typeVersionIds,
      List<UUID> itemIds,
      List<QueryFilter> filters) {}

  /**
   * Which column a count is grouped by.
   *
   * <p>Two, and both are columns of {@code inventory.item}. What they <i>mean</i> — a type key, a
   * place in a tree — is another block's to say, which is why this one answers with ids and counts
   * and nothing else.
   */
  enum CountColumn {
    /** {@code item_type_version_id}, which {@code catalog} turns into a type key. */
    TYPE_VERSION,
    /** {@code location_id}, which {@code locations} rolls up its tree. */
    LOCATION
  }

  /**
   * Returns one page of items matching a query.
   *
   * @param criteria what narrows it
   * @param after where to resume, or empty for the first page
   * @param sort the order to return them in, or {@code null} for the default. The field has already
   *     been checked against the tenant's allowlist by the caller; this block resolves it to a
   *     column and never to anything a caller typed
   * @param limit how many rows at most
   * @return the page, in the order asked for
   */
  Rows search(
      Criteria criteria, Optional<CursorCodec.Position> after, SortOrder sort, int limit);

  /**
   * How many matching items carry each value of one column (REQ-SRCH-002).
   *
   * <p>Every matching item, not one page of them: a facet counts the list and not the screen.
   * Items whose column is null — an item in no location — contribute no bucket, because "nowhere"
   * is not a place somebody can click on.
   *
   * @param criteria what narrows it. The caller has already removed this dimension's own filters,
   *     which is what makes the counts a control rather than an echo
   * @param column what to group by
   * @return the count per value, in no particular order; empty when nothing matches
   */
  Map<UUID, Long> countByColumn(Criteria criteria, CountColumn column);

  /**
   * How many matching items carry each value of one attribute (REQ-SRCH-002).
   *
   * <p>Read from {@code item_attr_index}, which is written in the same transaction as the item, so
   * a count is exact rather than eventually right (REQ-CORE-013). The value is rendered as text
   * whatever column it lives in — a facet's bucket is a token a filter takes back, and the filter
   * grammar is text.
   *
   * @param criteria what narrows it, this attribute's own filters already removed
   * @param fieldKey the attribute, already checked against the tenant's allowlist
   * @return the count per value, in no particular order; empty when nothing matches
   */
  Map<String, Long> countByAttribute(Criteria criteria, String fieldKey);

  /**
   * The ids of every matching item.
   *
   * <p>For the one facet this block cannot count: a tag lives in {@code tagging}, which can group
   * its assignments only over items it has been handed (ADR-0002). Unpaged and exact, decided with
   * the owner on 2026-09-14 — a cap would make a count quietly wrong, and OpenSearch is the engine
   * ADR-0008 appoints for large inventories.
   *
   * @param criteria what narrows it, the tag filters already removed
   * @return every matching id, in no particular order
   */
  List<UUID> matchingIds(Criteria criteria);

  /**
   * Everything about one item that a search engine may hold (REQ-SRCH-011).
   *
   * <p>Read whole and unredacted, because an index is a derived store that answers with ids and
   * never with content: the rows are re-loaded through the ordinary path, where row-level security
   * and the field visibility rules apply (REQ-SRCH-007). A document redacted for nobody in
   * particular would make the search miss text its reader is allowed to see.
   *
   * <p><b>Sealed values are not here.</b> A {@code sensitive} field is stored as ciphertext
   * (ADR-0019) and is never mirrored into {@code item_attr_index}, so it cannot reach a document
   * through this method either — an index over ciphertext matches nothing and leaks the fact
   * that there is something to match.
   *
   * @param itemId the item
   * @return what to index, or empty when the tenant has no such live item — which is the answer
   *     after a trashing, and is how an indexer learns to remove the document
   */
  Optional<SearchableItem> searchable(UUID itemId);

  /**
   * The searchable parts of one item.
   *
   * @param itemId the item
   * @param itemTypeVersionId the version it is written against, which {@code catalog} turns into a
   *     type and a category
   * @param name what it is called
   * @param description the prose, or {@code null}
   * @param notes the notes, or {@code null} (REQ-CORE-014)
   * @param locationId where it is, or {@code null}
   * @param attributeValues every mirrored attribute value rendered as text, sealed ones excluded
   * @param createdAt when it was created, which is the keyset order's first key
   * @param updatedAt when it last changed
   */
  record SearchableItem(
      UUID itemId,
      UUID itemTypeVersionId,
      String name,
      String description,
      String notes,
      UUID locationId,
      List<String> attributeValues,
      java.time.Instant createdAt,
      java.time.Instant updatedAt) {}

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
