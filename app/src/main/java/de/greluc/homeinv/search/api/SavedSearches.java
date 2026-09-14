/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.search.api;

import de.greluc.homeinv.inventory.api.ItemView;
import de.greluc.homeinv.platform.Page;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Named queries that appear as smart lists (REQ-SRCH-008).
 *
 * <h2>The tenant's, not the person's</h2>
 *
 * <p>Decided with the owner on 2026-09-14. 03 §3.4 lists a saved search beside a type, a label
 * template and a role as <i>configuration</i> — data in the running system — and REQ-SRCH-008 asks
 * for one to be usable as the source of a label print run or a stocktake, which somebody other than
 * its author has to be able to start. A household shares "everything that needs mending" the way it
 * shares the tag vocabulary.
 *
 * <p>So writing one is a change to what everybody sees ({@code SAVED_SEARCH_WRITE}, a member's) and
 * deleting one takes a list away from everybody ({@code SAVED_SEARCH_DELETE}, an administrator's).
 * Reading them needs only {@code SEARCH_QUERY}: a list of questions is not more sensitive than the
 * answers.
 *
 * <h2>It stores the query, not the answer</h2>
 *
 * <p>A saved search holds the text, the {@code filter} parameters verbatim and the sort key — the
 * query as 08 §8.2 spells it. Running one replays those parameters through the ordinary search, so
 * a smart list is exactly the list the same query would have produced typed by hand, facets and
 * degradation included. Nothing is cached: the point of a smart list is that it is current.
 */
public interface SavedSearches {

  /**
   * The tenant's saved searches, oldest first.
   *
   * @param cursor an opaque cursor from a previous page, or {@code null} for the first
   * @param limit how many at most
   * @return one page
   */
  Page<SavedSearchView> list(String cursor, int limit);

  /**
   * One saved search.
   *
   * @param id which one
   * @return it
   * @throws de.greluc.homeinv.platform.NotFoundException when the tenant has no such search
   */
  SavedSearchView get(UUID id);

  /**
   * Saves a query under a name.
   *
   * @param command the name and the query
   * @param actor the authenticated user
   * @return the saved search
   * @throws SavedSearchNameTakenException when the tenant already has one under that name,
   *     compared without regard to case
   * @throws IllegalArgumentException when a filter is not of the grammar 08 §8.2 spells
   */
  SavedSearchView create(SaveSearchCommand command, UUID actor);

  /**
   * Changes a saved search.
   *
   * @param id which one
   * @param command the new name and query
   * @param expectedVersion the version the caller acted on, or empty
   * @param actor the authenticated user
   * @return the changed search
   * @throws de.greluc.homeinv.platform.NotFoundException when the tenant has no such search
   * @throws de.greluc.homeinv.platform.StaleVersionException when somebody changed it meanwhile
   */
  SavedSearchView update(
      UUID id, SaveSearchCommand command, OptionalLong expectedVersion, UUID actor);

  /**
   * Removes a saved search.
   *
   * <p>Outright, not archived (decided with the owner 2026-09-14): nothing points at a saved
   * search, so keeping a tombstone would be retention without a reason. The audit log records who
   * removed it and when, which is what "nothing is lost silently" asks for here — the items behind
   * the question are untouched.
   *
   * @param id which one
   * @param expectedVersion the version the caller acted on, or empty
   * @param actor the authenticated user
   * @throws de.greluc.homeinv.platform.NotFoundException when the tenant has no such search
   * @throws de.greluc.homeinv.platform.StaleVersionException when somebody changed it meanwhile
   */
  void delete(UUID id, OptionalLong expectedVersion, UUID actor);

  /**
   * Runs a saved search and returns its current answer.
   *
   * <p>Through the ordinary search path, so a smart list is the list the same query typed by hand
   * would produce — the same row-level security, the same field visibility, the same cursor and the
   * same {@code meta.degraded}. Nothing is cached; a smart list that answered from yesterday would
   * be a report, not a list.
   *
   * @param id which saved search
   * @param cursor an opaque cursor from a previous page, or {@code null} for the first
   * @param limit how many at most
   * @return one page of the items it matches
   * @throws de.greluc.homeinv.platform.NotFoundException when the tenant has no such search
   */
  Page<ItemView> run(UUID id, String cursor, int limit);

  /**
   * What to save, or to save over.
   *
   * @param name what to call it; unique per tenant, compared without regard to case
   * @param text the {@code q} parameter, or {@code null}
   * @param filters the {@code filter} parameters verbatim, in the grammar of 08 §8.2
   * @param sort the {@code sort} parameter, or {@code null} for the default order
   */
  record SaveSearchCommand(String name, String text, List<String> filters, String sort) {}

  /**
   * A saved search as a client sees it.
   *
   * @param id its identifier
   * @param name what it is called
   * @param text the {@code q} parameter, or {@code null}
   * @param filters the {@code filter} parameters verbatim, so a client can show them and put them
   *     back into the query bar unchanged
   * @param sort the {@code sort} parameter, or {@code null}
   * @param createdAt when it was saved
   * @param updatedAt when it last changed
   * @param version the optimistic-lock version, which is also its ETag (08 §8.2)
   */
  record SavedSearchView(
      UUID id,
      String name,
      String text,
      List<String> filters,
      String sort,
      Instant createdAt,
      Instant updatedAt,
      long version) {}

  /**
   * The tenant already has a saved search under that name.
   *
   * <p>Its own exception rather than a shared one: the name is unique per tenant and
   * case-insensitively so, and what the caller has to change is the name — which the message says.
   * Answered {@code 409} with {@code name-taken}, like every other name a tenant owns.
   */
  class SavedSearchNameTakenException extends RuntimeException {

    private final transient String name;

    /**
     * Names what is taken, because that is what the caller has to change.
     *
     * @param name the name that is already in use
     */
    public SavedSearchNameTakenException(String name) {
      super("This tenant already has a saved search called '" + name + "'.");
      this.name = name;
    }

    /**
     * The name that is already in use.
     *
     * @return the name
     */
    public String getName() {
      return name;
    }
  }
}
