/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import de.greluc.homeinv.identity.api.AuthenticatedUser;
import de.greluc.homeinv.locations.api.LocationView;
import de.greluc.homeinv.locations.api.LocationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The {@code /api/v1/locations} endpoints. An adapter; every rule lives in the service. */
@RestController
@RequestMapping("/api/v1/locations")
@RequiredArgsConstructor
public class LocationController {

  private final LocationService locations;

  /**
   * Creates a location.
   *
   * @param request what to create
   * @param user the authenticated caller
   * @return the new location
   */
  @PostMapping
  public ResponseEntity<LocationView> create(
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
  @GetMapping("/{id}")
  public LocationView get(@PathVariable UUID id) {
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
  @PutMapping("/{id}")
  public LocationView rename(
      @PathVariable UUID id,
      @Valid @RequestBody RenameLocationRequest request,
      @AuthenticationPrincipal AuthenticatedUser user) {
    return locations.rename(id, request.name(), user.userId());
  }

  /**
   * The ids of a location and everything below it (REQ-CORE-049).
   *
   * @param id the location at the top of the subtree
   * @return the ids, including the location itself
   */
  @GetMapping("/{id}/subtree")
  public List<UUID> subtree(@PathVariable UUID id) {
    return locations.subtreeIds(id);
  }

  /**
   * Deletes a location, if nothing is inside it.
   *
   * @param id the location
   * @param user the authenticated caller
   * @return an empty 204
   */
  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(
      @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser user) {
    locations.delete(id, user.userId());
    return ResponseEntity.noContent().build();
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
