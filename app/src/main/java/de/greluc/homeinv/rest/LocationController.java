/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import de.greluc.homeinv.authorization.api.Permission;
import de.greluc.homeinv.authorization.api.RequiresPermission;
import de.greluc.homeinv.identity.api.AuthenticatedUser;
import de.greluc.homeinv.locations.api.LocationView;
import de.greluc.homeinv.locations.api.LocationService;
import de.greluc.homeinv.search.api.SearchService;
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
  private final SearchService search;

  /**
   * Creates a location.
   *
   * @param request what to create
   * @param user the authenticated caller
   * @return the new location
   */
  @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.LOCATION_CREATE)
  @CanFail(ProblemType.NOT_FOUND)
  // Redundant at run time — `ResponseEntity.created` sets the same status — and
  // not in the document, which would otherwise describe the 200 springdoc infers
  // from the return type for an endpoint that never answers one.
  @ResponseStatus(HttpStatus.CREATED)
  public ResponseEntity<LocationView> createLocation(
      @Valid @RequestBody CreateLocationRequest request,
      @AuthenticationPrincipal AuthenticatedUser user) {
    LocationView view =
        locations.create(
            new LocationService.CreateLocationCommand(
                request.id(), request.categoryId(), request.parentId(), request.name()),
            user.userId());
    return ResponseEntity.created(URI.create("/api/v1/locations/" + view.id())).body(view);
  }

  /**
   * Reads one location, with the names from the root down to it.
   *
   * @param id the location
   * @return the location
   */
  @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.LOCATION_READ)
  @CanFail(ProblemType.NOT_FOUND)
  public LocationView getLocation(@PathVariable UUID id) {
    return locations.get(id);
  }

  /**
   * Renames a location.
   *
   * @param id the location
   * @param request the new name
   * @param user the authenticated caller
   * @return the renamed location
   */
  @PutMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.LOCATION_UPDATE)
  @CanFail(ProblemType.NOT_FOUND)
  public LocationView renameLocation(
      @PathVariable UUID id,
      @Valid @RequestBody RenameLocationRequest request,
      @AuthenticationPrincipal AuthenticatedUser user) {
    return locations.rename(id, request.name(), user.userId());
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
  public SearchService.SearchResult itemsInLocation(
      @PathVariable UUID id,
      @RequestParam(required = false, defaultValue = "false") boolean includeSubtree,
      @RequestParam(required = false) @Size(max = 500) String cursor,
      @RequestParam(required = false, defaultValue = "50") @Positive @Max(200) int limit) {

    // The subtree is resolved HERE and not inside `inventory`, which must not
    // read the `locations` schema to work out which locations are below which
    // (REQ-NFR-021). `locations` answers the tree question; `inventory` answers
    // the item question; the adapter puts the two together, which is the one
    // thing an access adapter is for.
    List<UUID> scope = includeSubtree ? locations.subtreeIds(id) : List.of(id);

    return search.query(new SearchService.SearchRequest(null, "de", scope, cursor, limit));
  }

  /**
   * Deletes a location, if nothing is inside it.
   *
   * @param id the location
   * @param user the authenticated caller
   */
  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @RequiresPermission(Permission.LOCATION_DELETE)
  @CanFail({ProblemType.NOT_FOUND, ProblemType.RESOURCE_EXISTS})
  public void deleteLocation(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser user) {
    locations.delete(id, user.userId());
  }

  /**
   * The body of a creation.
   *
   * @param id the client's chosen id, or omitted
   * @param categoryId the category
   * @param parentId the parent, or omitted to create a root
   * @param name the name
   */
  public record CreateLocationRequest(
      UUID id,
      @NotNull UUID categoryId,
      UUID parentId,
      @NotBlank @Size(max = 300) String name) {}

  /**
   * The body of a rename.
   *
   * @param name the new name
   */
  public record RenameLocationRequest(@NotBlank @Size(max = 300) String name) {}
}
