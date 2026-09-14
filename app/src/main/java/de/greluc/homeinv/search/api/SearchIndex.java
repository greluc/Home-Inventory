/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.search.api;

import java.util.List;
import java.util.UUID;

/**
 * Where a search is actually run (REQ-SRCH-005).
 *
 * <p>Two implementations ship with the product: OpenSearch, which is the primary index, and
 * PostgreSQL, which is the fallback and the only one the {@code minimal} profile runs (ADR-0008).
 * The port is what makes that sentence true rather than aspirational — a third engine, or none,
 * changes an adapter and nothing else.
 *
 * <h2>Identifiers, never rows</h2>
 *
 * <p>{@link #find} answers with item ids and {@code search} re-loads each one from PostgreSQL
 * (REQ-SRCH-007). That is not indirection for its own sake; it is the reason a derived index is safe
 * to run at all:
 *
 * <ul>
 *   <li>the re-load passes through row-level security and the field visibility rules, so a stale or
 *       wrongly filtered index cannot cross a tenant boundary and cannot show a {@code sensitive}
 *       field to somebody who may not read one;
 *   <li>a stale index can therefore show one hit too many or too few, and never wrong content.
 * </ul>
 *
 * <p>An adapter that returned rows would move both of those guarantees into itself, once per engine.
 */
public interface SearchIndex {

  /**
   * What this engine is called, for the log and for the degraded signal.
   *
   * @return a short, stable name — {@code opensearch}, {@code postgresql}
   */
  String name();

  /**
   * Whether this index can answer right now.
   *
   * <p>Asked before a query rather than inferred from one that failed, so the fallback is a decision
   * and not an exception path. An adapter that cannot answer cheaply says {@code true} and lets
   * {@link #find} fail instead — a health check that is slower than the query it guards has made
   * things worse.
   *
   * @return true when a query is worth sending
   */
  boolean available();

  /**
   * Finds the items that match, most relevant first.
   *
   * @param query what to look for
   * @return the ids, in order, and where the next page starts
   * @throws RuntimeException when the engine cannot answer; the caller falls back and says so
   *     through {@code meta.degraded} (REQ-SRCH-006)
   */
  Hits find(Query query);

  /**
   * What to look for.
   *
   * @param text the query text; blank matches everything, paged the same way
   * @param language {@code de} or {@code en}. It decides which analysis the text gets, and for the
   *     PostgreSQL adapter which generated vector is read (ADR-0047)
   * @param locationIds restricts the result to items in these places; empty means the whole tenant.
   *     The subtree is resolved by {@code locations} before it gets here, because working out which
   *     places lie below which is that block's question and not this one's
   * @param after where to resume, or empty for the first page. A <em>position</em> and not a signed
   *     cursor string: signing is the service's job, so that one mechanism covers both engines and
   *     an adapter cannot invent a cursor nobody verified (REQ-SEC-106, REQ-SRCH-009). It suits both
   *     — keyset pagination in PostgreSQL and {@code search_after} in OpenSearch are the same idea
   * @param sort the order to return them in, or {@code null} for the default — oldest first, which
   *     is the order the keyset cursor resumes in when nobody asked for another
   * @param typeVersionIds the item-type versions to restrict to, or empty for no restriction.
   *     Resolved from the keys a caller wrote by {@code catalog}, which owns both the key and the
   *     version chain — an item is written against a version, not against a type
   * @param itemIds the items to restrict to, or empty for no restriction. This is how a tag filter
   *     arrives: {@code tagging} owns the assignment and answers with ids, so an engine narrowing
   *     by tag never has to know what a tag is
   * @param filters conditions on attributes, all of which must hold. Already checked against the
   *     tenant's allowlist by the caller, and holding attribute conditions only — every other
   *     dimension has been resolved to one of the id lists above
   * @param limit how many at most
   */
  record Query(
      String text,
      String language,
      List<UUID> locationIds,
      List<UUID> typeVersionIds,
      List<UUID> itemIds,
      java.util.Optional<de.greluc.homeinv.platform.CursorCodec.Position> after,
      de.greluc.homeinv.platform.SortOrder sort,
      List<de.greluc.homeinv.platform.QueryFilter> filters,
      int limit) {}


  /**
   * What was found.
   *
   * @param itemIds the matching items, in the order they are to be shown
   * @param last where the next page would resume, or empty when this was the last. The service
   *     signs it into a cursor
   */
  record Hits(
      List<UUID> itemIds,
      java.util.Optional<de.greluc.homeinv.platform.CursorCodec.Position> last) {}
}
