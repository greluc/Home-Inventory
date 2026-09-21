/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import io.swagger.v3.oas.annotations.tags.Tag;
import de.greluc.homeinv.authorization.api.Permission;
import de.greluc.homeinv.authorization.api.RequiresPermission;
import de.greluc.homeinv.identity.api.AuthenticatedUser;
import de.greluc.homeinv.inventory.api.ItemView;
import de.greluc.homeinv.platform.Page;
import de.greluc.homeinv.search.api.SavedSearches;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Named queries usable as smart lists (REQ-SRCH-008, 08 §8.1).
 *
 * <p>At {@code /api/v1/saved-searches}, which 08 §8.1 has reserved since the first commit.
 * <i>`ItemController` said `/search` was the reserved path until 2026-09-14; the chapter says
 * `/saved-searches` and the chapter is the contract.</i>
 *
 * <p>A saved search belongs to the tenant and everybody in it sees the same list, so writing one is
 * a member's capability and deleting one is an administrator's — it takes a list away from
 * everybody. Reading them needs only {@code SEARCH_QUERY}: a list of questions is not more
 * sensitive than the answers to them.
 *
 * <p>{@code {id}/items} runs it. The answer is the ordinary search envelope, cursor, facets,
 * {@code meta.degraded} and all, because a smart list is the list the same query typed by hand
 * would produce and not a second kind of thing.
 */
@Tag(name = "Saved searches", description = "Named queries, usable as smart lists.")
@RestController
@RequestMapping("/api/v1/saved-searches")
@RequiredArgsConstructor
public class SavedSearchController {

  private final SavedSearches searches;

  /**
   * The tenant's saved searches, oldest first.
   *
   * @param cursor an opaque cursor from a previous response, or omitted for the first page
   * @param limit how many at most; capped at 200 by the service
   * @return one page
   */
  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.SEARCH_QUERY)
  @CanFail(ProblemType.MALFORMED_REQUEST)
  public Page<SavedSearchResponse> listSavedSearches(
      @RequestParam(required = false) @Size(max = 500) String cursor,
      @RequestParam(required = false, defaultValue = "50") @Positive @Max(200) int limit) {
    Page<SavedSearches.SavedSearchView> page = searches.list(cursor, limit);
    return new Page<>(
        page.data().stream().map(SavedSearchResponse::of).toList(),
        page.page(),
        page.meta(),
        page.facets());
  }

  /**
   * One saved search.
   *
   * @param id which one
   * @return it
   */
  @GetMapping(path = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.SEARCH_QUERY)
  @CanFail(ProblemType.NOT_FOUND)
  public ResponseEntity<SavedSearchResponse> savedSearch(@PathVariable UUID id) {
    // The ETag a write has to send back (REQ-API-004): the row's version, not a
    // hash of the body.
    SavedSearches.SavedSearchView view = searches.get(id);
    return ResponseEntity.ok().eTag(EntityTags.of(view.version())).body(SavedSearchResponse.of(view));
  }

  /**
   * Saves a query under a name.
   *
   * @param request the name and the query
   * @param user the authenticated caller
   * @return the saved search, with a {@code Location} naming it
   */
  @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.SAVED_SEARCH_WRITE)
  @CanFail({ProblemType.NAME_TAKEN, ProblemType.VALIDATION_FAILED})
  @ResponseStatus(HttpStatus.CREATED)
  public ResponseEntity<SavedSearchResponse> createSavedSearch(
      @Valid @RequestBody SavedSearchRequest request,
      @AuthenticationPrincipal AuthenticatedUser user) {
    SavedSearches.SavedSearchView saved =
        searches.create(
            new SavedSearches.SaveSearchCommand(
                request.name(), request.q(), request.filters(), request.sort()),
            user.userId());
    return ResponseEntity.created(URI.create("/api/v1/saved-searches/" + saved.id()))
        .eTag(EntityTags.of(saved.version()))
        .body(SavedSearchResponse.of(saved));
  }

  /**
   * Changes a saved search.
   *
   * @param id which one
   * @param request the new name and query
   * @param http the request, for the required {@code If-Match} header (08 §8.2)
   * @param user the authenticated caller
   * @return the changed search
   */
  @PutMapping(path = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.SAVED_SEARCH_WRITE)
  @CanFail({
    ProblemType.NOT_FOUND,
    ProblemType.NAME_TAKEN,
    ProblemType.VALIDATION_FAILED,
    ProblemType.PRECONDITION_REQUIRED,
    ProblemType.PRECONDITION_FAILED
  })
  public ResponseEntity<SavedSearchResponse> updateSavedSearch(
      @PathVariable UUID id,
      @Valid @RequestBody SavedSearchRequest request,
      jakarta.servlet.http.HttpServletRequest http,
      @AuthenticationPrincipal AuthenticatedUser user) {
    SavedSearches.SavedSearchView saved =
        searches.update(
            id,
            new SavedSearches.SaveSearchCommand(
                request.name(), request.q(), request.filters(), request.sort()),
            EntityTags.required(http),
            user.userId());
    return ResponseEntity.ok().eTag(EntityTags.of(saved.version())).body(SavedSearchResponse.of(saved));
  }

  /**
   * Removes a saved search.
   *
   * @param id which one
   * @param http the request, for the required {@code If-Match} header
   * @param user the authenticated caller
   */
  @DeleteMapping(path = "/{id}")
  @RequiresPermission(Permission.SAVED_SEARCH_DELETE)
  @CanFail({
    ProblemType.NOT_FOUND,
    ProblemType.PRECONDITION_REQUIRED,
    ProblemType.PRECONDITION_FAILED
  })
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteSavedSearch(
      @PathVariable UUID id,
      jakarta.servlet.http.HttpServletRequest http,
      @AuthenticationPrincipal AuthenticatedUser user) {
    searches.delete(id, EntityTags.required(http), user.userId());
  }

  /**
   * Runs a saved search.
   *
   * <p>The ordinary search envelope: a smart list is the list the same query typed by hand would
   * produce, so a client renders it with the code it already has.
   *
   * @param id which saved search
   * @param cursor an opaque cursor from a previous response, or omitted for the first page
   * @param limit how many at most
   * @return one page of the items it matches
   */
  @GetMapping(path = "/{id}/items", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.SEARCH_QUERY)
  @CanFail({ProblemType.NOT_FOUND, ProblemType.MALFORMED_REQUEST})
  public Page<ItemView> savedSearchItems(
      @PathVariable UUID id,
      @RequestParam(required = false) @Size(max = 500) String cursor,
      @RequestParam(required = false, defaultValue = "50") @Positive @Max(200) int limit) {
    return searches.run(id, cursor, limit);
  }

  /**
   * The body of a save.
   *
   * @param name what to call it; unique per tenant, compared without regard to case
   * @param q the text to search for, or omitted
   * @param filters the {@code filter} parameters verbatim, in the grammar of 08 §8.2 — the same
   *     strings a client would put in the query bar, so that showing one and running one need no
   *     translation
   * @param sort the sort key, a leading minus for descending, or omitted for the default order
   */
  /**
   * A saved search as the wire spells it.
   *
   * <p>The same names a query takes: {@code q}, {@code filters}, {@code sort}. The port's view
   * calls the first one {@code text}, which is what it is; here it is what a client typed, and a
   * client that sends {@code q} and is answered {@code text} cannot put a saved search back into
   * the query bar without translating it.
   *
   * @param id its identifier
   * @param name what it is called
   * @param q the text to search for, or {@code null}
   * @param filters the {@code filter} parameters verbatim
   * @param sort the sort key, or {@code null}
   * @param createdAt when it was saved
   * @param updatedAt when it last changed
   * @param version the optimistic-lock version, which is also its ETag
   */
  public record SavedSearchResponse(
      UUID id,
      String name,
      String q,
      List<String> filters,
      String sort,
      java.time.Instant createdAt,
      java.time.Instant updatedAt,
      long version) {

    /**
     * Renames the one member whose wire name differs.
     *
     * @param view what the application layer answered
     * @return the same saved search, in the wire's names
     */
    static SavedSearchResponse of(SavedSearches.SavedSearchView view) {
      return new SavedSearchResponse(
          view.id(),
          view.name(),
          view.text(),
          view.filters(),
          view.sort(),
          view.createdAt(),
          view.updatedAt(),
          view.version());
    }
  }

  public record SavedSearchRequest(
      @NotBlank @Size(max = 200) String name,
      @Size(max = 500) String q,
      @Size(max = 20) List<@Size(max = 200) String> filters,
      @Size(max = 100) String sort) {}
}
