/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.notification.api;

import java.util.Set;
import java.util.UUID;

/**
 * Which things a reminder rule watches, when it names a saved search (REQ-NOTI-001, REQ-SRCH-008).
 *
 * <h2>Why this is a port and not a call to {@code SavedSearches}</h2>
 *
 * <p>Because that call is a cycle, and the module check proved it. {@code inventory} implements
 * {@link ReminderSource}, so {@code inventory → notification}; {@code search} answers with items,
 * so {@code search → inventory}. A direct call from here to {@code SavedSearches} adds {@code
 * notification → search} and closes the loop — a three-hop cycle that is invisible when you look at
 * any one of the three edges.
 *
 * <p>So the direction is inverted, exactly as it is for {@link ReminderSource}: {@code
 * notification} says what it needs, {@code search} implements it, and every edge points <b>into</b>
 * this block. Two ports rather than one dependency, and the pattern is the same because the problem
 * is (ADR-0002, REQ-NFR-019…024).
 *
 * <h2>What it hands back</h2>
 *
 * <p>Ids and nothing else. A block that received rows in order to read one field from them would
 * depend on whatever those rows are, which is the first form of this cycle — the run narrows by
 * intersecting ids, and it never needs to know what an item is.
 */
public interface ReminderScope {

  /**
   * The ids a saved search currently matches.
   *
   * @param savedSearchId which search
   * @param limit how many at most; capped at 200 like every collection (REQ-NFR-010)
   * @return the matching ids, or an empty set when the search matches nothing. An <b>empty</b> set
   *     and "no narrowing at all" are different, and the caller must not confuse them: a rule whose
   *     search matches nothing reminds about nothing, not about everything
   */
  Set<UUID> idsMatching(UUID savedSearchId, int limit);

  /**
   * Whether the tenant in context has such a saved search.
   *
   * <p>Asked when a rule is written, so that naming another tenant's search is the {@code 404} an
   * unknown one is (REQ-SEC-025) rather than a foreign-key violation from the database.
   *
   * @param savedSearchId which search
   * @return whether it is there
   */
  boolean exists(UUID savedSearchId);
}
