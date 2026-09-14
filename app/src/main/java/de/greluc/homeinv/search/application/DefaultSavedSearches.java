/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.search.application;

import de.greluc.homeinv.audit.api.RevisionLog;
import de.greluc.homeinv.inventory.api.ItemView;
import de.greluc.homeinv.platform.CursorCodec;
import de.greluc.homeinv.platform.NotFoundException;
import de.greluc.homeinv.platform.Page;
import de.greluc.homeinv.platform.QueryFilter;
import de.greluc.homeinv.platform.Versions;
import de.greluc.homeinv.search.api.SavedSearches;
import de.greluc.homeinv.search.api.SearchService;
import de.greluc.homeinv.search.infrastructure.SavedSearchQueries;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Named queries, and running them (REQ-SRCH-008).
 *
 * <h2>It stores a question and replays it</h2>
 *
 * <p>A saved search holds the text, the {@code filter} parameters verbatim and the sort key, and
 * {@link #run} hands them to the ordinary {@link SearchService}. So a smart list is exactly the
 * list the same query typed by hand would produce — the same row-level security, the same field
 * visibility, the same cursor, the same {@code meta.degraded}. Nothing is cached; a smart list that
 * answered from yesterday would be a report.
 *
 * <p>The filters are checked when they are <b>saved</b> as well as when they are run, against the
 * grammar of 08 §8.2. Saving one that cannot be parsed would be a list that fails whenever anybody
 * opens it, and the person who could fix it is the one saving it now.
 */
@Service
@RequiredArgsConstructor
public class DefaultSavedSearches implements SavedSearches {

  /** More than this per page and the response stops being a page. */
  private static final int MAX_PAGE = 200;

  /** How many filters one saved search may carry, the same ceiling the query parameter has. */
  private static final int MAX_FILTERS = 20;

  /** How long one filter may be, likewise. */
  private static final int MAX_FILTER_LENGTH = 200;

  /**
   * What the cursor of this listing is bound to.
   *
   * <p>A constant, because the listing takes no query: every page of it is the same question and a
   * cursor from one is valid on the next. The signing still happens, so a forged cursor is refused
   * (REQ-SEC-106).
   */
  private static final String CURSOR_FINGERPRINT = "saved-searches";

  private final SavedSearchQueries searches;

  /** Where a saved search is actually answered; the same path a typed query takes. */
  private final SearchService search;

  /** Every change is a revision, which is what makes a deletion recoverable as a record. */
  private final RevisionLog revisions;

  private final CursorCodec cursors;

  @Override
  @Transactional(readOnly = true)
  public Page<SavedSearchView> list(String cursor, int limit) {
    int size = Math.clamp(limit, 1, MAX_PAGE);
    Optional<CursorCodec.Position> after =
        cursor == null || cursor.isBlank()
            ? Optional.empty()
            : Optional.of(cursors.decode(cursor, CURSOR_FINGERPRINT));

    // One more than asked for, so the caller learns whether another page exists
    // without a second count query.
    List<SavedSearchView> rows =
        searches.list(
            after.map(CursorCodec.Position::createdAt).orElse(null),
            after.map(CursorCodec.Position::id).orElse(null),
            size + 1);

    boolean hasMore = rows.size() > size;
    List<SavedSearchView> page = hasMore ? rows.subList(0, size) : rows;
    String next =
        hasMore
            ? cursors.encode(
                CursorCodec.Position.of(
                    page.getLast().createdAt(), page.getLast().id()),
                CURSOR_FINGERPRINT)
            : null;
    return Page.of(page, next);
  }

  @Override
  @Transactional(readOnly = true)
  public SavedSearchView get(UUID id) {
    return searches.byId(id).orElseThrow(() -> new NotFoundException("saved search", id));
  }

  @Override
  @Transactional
  public SavedSearchView create(SaveSearchCommand command, UUID actor) {
    String name = requireName(command.name());
    List<String> filters = checked(command.filters());
    if (searches.nameTaken(name, null)) {
      throw new SavedSearchNameTakenException(name);
    }

    UUID id = UUID.randomUUID();
    searches.insert(id, name, blankToNull(command.text()), filters, blankToNull(command.sort()), actor);
    SavedSearchView saved = get(id);
    revisions.record(
        RevisionLog.EntityType.SAVED_SEARCH,
        id,
        RevisionLog.ChangeKind.CREATED,
        snapshot(saved),
        actor);
    return saved;
  }

  @Override
  @Transactional
  public SavedSearchView update(
      UUID id, SaveSearchCommand command, OptionalLong expectedVersion, UUID actor) {
    SavedSearchView current = get(id);
    Versions.requireCurrent("saved search", id, expectedVersion, current.version());

    String name = requireName(command.name());
    List<String> filters = checked(command.filters());
    // Ignoring this one's own id, so that keeping the name while changing the
    // query is not a conflict with itself.
    if (searches.nameTaken(name, id)) {
      throw new SavedSearchNameTakenException(name);
    }

    searches.update(id, name, blankToNull(command.text()), filters, blankToNull(command.sort()), actor);
    SavedSearchView saved = get(id);
    revisions.record(
        RevisionLog.EntityType.SAVED_SEARCH,
        id,
        RevisionLog.ChangeKind.UPDATED,
        snapshot(saved),
        actor);
    return saved;
  }

  @Override
  @Transactional
  public void delete(UUID id, OptionalLong expectedVersion, UUID actor) {
    SavedSearchView current = get(id);
    Versions.requireCurrent("saved search", id, expectedVersion, current.version());

    // The revision first, while there is still something to snapshot. Afterwards
    // the row is gone and "who removed the list called Repairs" would be a
    // question with no answer (CLAUDE.md rule 12).
    revisions.record(
        RevisionLog.EntityType.SAVED_SEARCH,
        id,
        RevisionLog.ChangeKind.PURGED,
        snapshot(current),
        actor);
    searches.delete(id);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<ItemView> run(UUID id, String cursor, int limit) {
    SavedSearchView saved = get(id);
    return search.query(
        new SearchService.SearchRequest(
            saved.text(),
            "de",
            List.of(),
            cursor,
            de.greluc.homeinv.platform.SortOrder.parse(saved.sort()),
            QueryFilter.parseAll(saved.filters(), MAX_FILTERS, MAX_FILTER_LENGTH),
            List.of(),
            limit));
  }

  /**
   * Checks the filters against the grammar without keeping the result.
   *
   * <p>Parsed here and stored as text: what is saved is what a client would type, so a client can
   * show it and put it back into the query bar unchanged. Parsing it now is how a query that cannot
   * be run is refused by the person who can still fix it.
   *
   * @param filters what was given, possibly none
   * @return the same strings, trimmed of nothing
   * @throws IllegalArgumentException when one is not of the grammar
   */
  private static List<String> checked(List<String> filters) {
    List<String> given = filters == null ? List.of() : filters;
    QueryFilter.parseAll(given, MAX_FILTERS, MAX_FILTER_LENGTH);
    return given;
  }

  /**
   * A name that is actually a name.
   *
   * @param name what was given
   * @return it, trimmed
   * @throws IllegalArgumentException when it is blank
   */
  private static String requireName(String name) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("A saved search needs a name");
    }
    return name.trim();
  }

  /**
   * Turns a blank into a null, because an empty query text is not a query.
   *
   * @param value what was given
   * @return it, or {@code null} when it holds nothing
   */
  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  /**
   * What the revision log records.
   *
   * @param saved the state to remember
   * @return a JSON object naming the query
   */
  private static String snapshot(SavedSearchView saved) {
    return "{\"name\":\"%s\",\"filters\":%d}".formatted(saved.name(), saved.filters().size());
  }
}
