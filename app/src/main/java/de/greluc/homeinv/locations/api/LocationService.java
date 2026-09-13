/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.locations.api;

import java.util.List;
import java.util.UUID;

/**
 * What can be done with storage locations.
 *
 * <p>Published, with the implementation behind it. There is deliberately no move operation at stage
 * 0: cycles are then impossible by construction rather than by a check, because a parent is fixed at
 * creation (REQ-CORE-045).
 */
public interface LocationService {

  /**
   * Creates a location, as a root or below an existing one.
   *
   * @param command what to create
   * @param actor the authenticated user
   * @return the new location
   * @throws TooDeepException when the tree would exceed its depth limit
   */
  LocationView create(CreateLocationCommand command, UUID actor);

  /**
   * Reads one location, with the names from the root down to it (REQ-CORE-044).
   *
   * @param id the location
   * @return the location
   */
  LocationView get(UUID id);

  /**
   * Renames a location, without touching the materialised path.
   *
   * @param id the location
   * @param name the new name
   * @param actor the authenticated user
   * @return the renamed location
   */
  LocationView rename(UUID id, String name, UUID actor);

  /**
   * Deletes a location, if nothing is inside it.
   *
   * @param id the location
   * @param actor the authenticated user
   * @throws LocationNotEmptyException when a place or an item is still inside it
   */
  void delete(UUID id, UUID actor);

  /**
   * The ids of a location and everything below it (REQ-CORE-049).
   *
   * @param id the location at the top of the subtree
   * @return the ids, including the location itself
   */
  List<UUID> subtreeIds(UUID id);

  /**
   * What is needed to create a location.
   *
   * @param id the client's chosen id, or {@code null} to have one generated
   * @param categoryId the category, from the tenant's seeded set
   * @param parentId the parent, or {@code null} to create a root
   * @param name the name; must not be blank
   */
  record CreateLocationCommand(
      UUID id, UUID categoryId, UUID parentId, String name, String attributes) {}

  /**
   * One page of the tenant's locations, oldest first.
   *
   * <p>The whole tree rather than one level: a client building a picker needs the shape, and a
   * household's tree is tens of rows, not thousands. It is paged regardless, because
   * {@code REQ-NFR-010} admits no endpoint that loads a collection without a bound — and because a
   * warehouse is a household with four more digits.
   *
   * <p>Ordered by creation and not by path. A keyset cursor needs an order that does not change
   * under the client's feet, and renaming a location changes its label and therefore its place in a
   * path ordering. Each row carries {@code parentId} and {@code ancestors}, which is what a tree is
   * assembled from.
   *
   * @param cursor an opaque cursor from a previous page, or {@code null} for the first
   * @param limit how many at most; capped at 200
   * @return the page and a cursor for the next one
   */
  LocationPage list(String cursor, int limit);

  /**
   * One page of locations.
   *
   * @param items the locations on this page
   * @param nextCursor the cursor for the next page, or {@code null} when this was the last
   */
  record LocationPage(List<LocationView> items, String nextCursor) {}
}
