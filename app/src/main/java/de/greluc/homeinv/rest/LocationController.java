/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import de.greluc.homeinv.platform.Page;
import de.greluc.homeinv.catalog.api.LocationCategories;
import de.greluc.homeinv.authorization.api.Permission;
import de.greluc.homeinv.authorization.api.RequiresPermission;
import de.greluc.homeinv.identity.api.AuthenticatedUser;
import de.greluc.homeinv.locations.api.LocationView;
import de.greluc.homeinv.locations.api.LocationService;
import de.greluc.homeinv.search.api.SearchService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The {@code /api/v1/locations} endpoints. An adapter; every rule lives in the service. */
@RestController
@RequestMapping("/api/v1/locations")
@RequiredArgsConstructor
public class LocationController {

  private final LocationService locations;
  private final LocationCategories categories;
  private final SearchService search;

  /**
   * The mapper the request body is re-serialised with, for an {@code Idempotency-Key}'s hash.
   *
   * <p>The application's own, so what is hashed is what this service would have written: a second
   * mapper configured differently would make the same request hash two ways.
   */
  private final tools.jackson.databind.ObjectMapper json;

  /**
   * Creates a location.
   *
   * @param request what to create
   * @param user the authenticated caller
   * @return the new location
   */
  @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.LOCATION_CREATE)
  @CanFail({ProblemType.NOT_FOUND, ProblemType.NAME_TAKEN})
  // Redundant at run time — `ResponseEntity.created` sets the same status — and
  // not in the document, which would otherwise describe the 200 springdoc infers
  // from the return type for an endpoint that never answers one.
  @ResponseStatus(HttpStatus.CREATED)
  public ResponseEntity<LocationView> createLocation(
      @Valid @RequestBody CreateLocationRequest request,
      HttpServletRequest http,
      @AuthenticationPrincipal AuthenticatedUser user) {
    LocationView view =
        locations.create(
            new LocationService.CreateLocationCommand(
                request.id(),
                request.categoryId(),
                request.parentId(),
                request.name(),
                request.attributes()),
            IdempotencyKeys.from(http, request, json),
            user.userId());
    return ResponseEntity.created(URI.create("/api/v1/locations/" + view.id()))
        .eTag(EntityTags.of(view.version()))
        .body(view);
  }

  /**
   * One page of the tenant's locations, oldest first.
   *
   * <p>The whole tree rather than one level. A client building a picker needs the shape, and each
   * row carries its parent and its readable path, which is what a tree is assembled from.
   *
   * @param cursor an opaque cursor from a previous page, or omitted for the first
   * @param limit how many at most; capped at 200 by the service
   * @return the page and a cursor for the next one
   */
  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.LOCATION_READ)
  @CanFail(ProblemType.MALFORMED_REQUEST)
  public Page<LocationView> listLocations(
      @RequestParam(required = false) @Size(max = 500) String cursor,
      @RequestParam(required = false, defaultValue = "50") @Positive @Max(200) int limit) {
    return locations.list(cursor, limit);
  }

  /**
   * One page of the kinds of place a location can be.
   *
   * <p>Thirteen shipped keys at stage 0 (REQ-CORE-042), the same for every tenant. The client
   * translates the key and sorts by the result: they are interface text, interface text lives in a
   * resource bundle (REQ-NFR-032), and only the client knows what order the reader's language puts
   * thirteen words in.
   *
   * <p>Under {@code /api/v1/locations/categories} rather than at the top level, because that is what
   * they are for — a category with no location to put it on is not a thing this API offers.
   *
   * @param cursor an opaque cursor from a previous page, or omitted for the first
   * @param limit how many at most; capped at 200 by the service
   * @return the page and a cursor for the next one
   */
  @GetMapping(value = "/categories", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.LOCATION_READ)
  @CanFail(ProblemType.MALFORMED_REQUEST)
  public Page<de.greluc.homeinv.catalog.api.LocationCategoryView> listLocationCategories(
      @RequestParam(required = false) @Size(max = 500) String cursor,
      @RequestParam(required = false, defaultValue = "50") @Positive @Max(200) int limit) {
    return categories.list(cursor, limit);
  }

  /**
   * Reads one location, with the names from the root down to it.
   *
   * <p>Carries the {@code ETag} a write has to send back (REQ-API-004).
   *
   * @param id the location
   * @return the location and its entity tag
   */
  @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.LOCATION_READ)
  @CanFail(ProblemType.NOT_FOUND)
  public ResponseEntity<LocationView> getLocation(@PathVariable UUID id) {
    LocationView view = locations.get(id);
    return ResponseEntity.ok().eTag(EntityTags.of(view.version())).body(view);
  }

  /**
   * Renames a location.
   *
   * <p>Requires {@code If-Match} (REQ-API-004): renaming is exactly the operation two people do
   * to the same place at the same time.
   *
   * @param id the location
   * @param request the new name
   * @param http the request, for its {@code If-Match}
   * @param user the authenticated caller
   * @return the renamed location, with its new entity tag
   */
  @PutMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.LOCATION_UPDATE)
  @CanFail({
    ProblemType.NOT_FOUND,
    ProblemType.NAME_TAKEN,
    ProblemType.PRECONDITION_REQUIRED,
    ProblemType.PRECONDITION_FAILED
  })
  public ResponseEntity<LocationView> renameLocation(
      @PathVariable UUID id,
      @Valid @RequestBody RenameLocationRequest request,
      HttpServletRequest http,
      @AuthenticationPrincipal AuthenticatedUser user) {
    LocationView view =
        locations.rename(id, request.name(), EntityTags.required(http), user.userId());
    return ResponseEntity.ok().eTag(EntityTags.of(view.version())).body(view);
  }

  /**
   * Moves a location, with everything under it (REQ-CORE-043).
   *
   * <p>Its own sub-path rather than a {@code PATCH} on the location: what changes is one
   * relationship, and a client that sends the whole location to move it is a client that can rename
   * it by accident. {@code parentId} omitted or null makes it a root. 08 §8.1 names this endpoint,
   * and the spelling follows every other verb in this API — {@code /archive}, {@code /publish},
   * {@code /restore}.
   *
   * <p>{@code POST} and still idempotent: moving a place to where it already is answers {@code 200}
   * and changes nothing, so a client retrying a request whose answer it never saw is not told it
   * failed.
   *
   * <p>Requires {@code If-Match} like the other writes on a place. A `POST` rather than a `PUT`
   * changes nothing about that: what the rule is protecting is the resource, not the verb.
   *
   * @param id the location to move
   * @param request where to put it
   * @param http the request, for its {@code If-Match}
   * @param user the authenticated caller
   * @return the location as it now stands, with its new entity tag
   */
  @PostMapping(value = "/{id}/move", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.LOCATION_UPDATE)
  @CanFail({
    ProblemType.NOT_FOUND,
    ProblemType.NAME_TAKEN,
    ProblemType.INVALID_MOVE,
    ProblemType.VALIDATION_FAILED,
    ProblemType.PRECONDITION_REQUIRED,
    ProblemType.PRECONDITION_FAILED
  })
  public ResponseEntity<LocationView> moveLocation(
      @PathVariable UUID id,
      @Valid @RequestBody MoveLocationRequest request,
      HttpServletRequest http,
      @AuthenticationPrincipal AuthenticatedUser user) {
    LocationView view =
        locations.move(id, request.parentId(), EntityTags.required(http), user.userId());
    return ResponseEntity.ok().eTag(EntityTags.of(view.version())).body(view);
  }

  /**
   * The items in a location, optionally including everything below it (REQ-CORE-049).
   *
   * <p>This replaced a {@code /subtree} endpoint that returned a bare list of location ids. That
   * shape had two problems and neither was cosmetic: it answered a question nobody asked — the
   * requirement is about <em>items</em> — and it returned an unbounded collection, which
   * {@code REQ-NFR-010} forbids for every endpoint without exception.
   *
   * <p>The paging is the same cursor mechanism search uses, deliberately: one implementation, one
   * discipline, and a cursor that is bound to the location filter as well as to the text, so a
   * cursor from one location's page is refused on another's (REQ-SRCH-009).
   *
   * @param id the location
   * @param includeSubtree whether items in locations below this one count as well
   * @param cursor an opaque cursor from a previous page
   * @param limit how many at most; capped at 200 by the service
   * @return the page and a cursor for the next one
   */
  @GetMapping(value = "/{id}/items", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.ITEM_READ)
  @CanFail({ProblemType.NOT_FOUND, ProblemType.MALFORMED_REQUEST})
  public Page<de.greluc.homeinv.inventory.api.ItemView> itemsInLocation(
      @PathVariable UUID id,
      @RequestParam(required = false, defaultValue = "false") boolean includeSubtree,
      @RequestParam(required = false) @Size(max = 500) String cursor,
      @RequestParam(required = false, defaultValue = "50") @Positive @Max(200) int limit) {

    // Asked first, and for its answer rather than its value: a place this
    // tenant cannot see is a 404, which this endpoint has declared since it was
    // written and could not produce until 2026-09-14. Without it the search ran
    // over a scope of one id that matches nothing and answered an empty page,
    // which reads as "this place is empty" rather than "there is no such place".
    locations.get(id);

    // The subtree is resolved HERE and not inside `inventory`, which must not
    // read the `locations` schema to work out which locations are below which
    // (REQ-NFR-021). `locations` answers the tree question; `inventory` answers
    // the item question; the adapter puts the two together, which is the one
    // thing an access adapter is for.
    List<UUID> scope = includeSubtree ? locations.subtreeIds(id) : List.of(id);

    return search.query(
        new SearchService.SearchRequest(
            null, "de", scope, cursor, null, List.of(), List.of(), limit));
  }

  /**
   * Deletes a location, if nothing is inside it.
   *
   * @param id the location
   * @param http the request, for its {@code If-Match}
   * @param user the authenticated caller
   */
  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @RequiresPermission(Permission.LOCATION_DELETE)
  @CanFail({
    ProblemType.NOT_FOUND,
    ProblemType.RESOURCE_EXISTS,
    ProblemType.PRECONDITION_REQUIRED,
    ProblemType.PRECONDITION_FAILED
  })
  public void deleteLocation(
      @PathVariable UUID id,
      HttpServletRequest http,
      @AuthenticationPrincipal AuthenticatedUser user) {
    locations.delete(id, EntityTags.required(http), user.userId());
  }

  /**
   * The body of a creation.
   *
   * @param id the client's chosen id, or omitted
   * @param categoryId the category
   * @param parentId the parent, or omitted to create a root
   * @param name the name
   * @param attributes the fields the category declares, as a JSON object. Checked against the
   *     category version's schema, and an offending value is a {@code 422} naming its path
   *     (REQ-CORE-041, REQ-CORE-005)
   */
  public record CreateLocationRequest(
      UUID id,
      @NotNull UUID categoryId,
      UUID parentId,
      @NotBlank @Size(max = 300) String name,
      @Size(max = 65_536) String attributes) {}

  /**
   * Where a location is to be moved.
   *
   * @param parentId the new parent, or null to make it a root. A client that means "out of
   *     everything" says so with null rather than with an absent field, and both are read the same
   *     way
   */
  public record MoveLocationRequest(UUID parentId) {}

  /**
   * The body of a rename.
   *
   * @param name the new name
   */
  public record RenameLocationRequest(@NotBlank @Size(max = 300) String name) {}
}
