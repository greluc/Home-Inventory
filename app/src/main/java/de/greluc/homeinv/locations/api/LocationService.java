/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.locations.api;

import de.greluc.homeinv.platform.Page;
import de.greluc.homeinv.idempotency.api.RequestKey;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
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
   * @param idempotency the {@code Idempotency-Key} the caller sent and the hash of what it
   *     sent with it, or empty when it sent none. A repeat carrying a spent key answers what
   *     that key answered before and creates nothing (REQ-API-005)

   * @param actor the authenticated user
   * @return the new location
   * @throws TooDeepException when the tree would exceed its depth limit
   */
  LocationView create(
      CreateLocationCommand command, Optional<RequestKey> idempotency, UUID actor);

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
   * @param expectedVersion the version the caller acted on, from the {@code ETag} of its last
   *     read; empty skips the check, which is what an internal caller with no screen to go
   *     stale passes (REQ-API-004)
   * @param actor the authenticated user
   * @return the renamed location
   */
  LocationView rename(UUID id, String name, OptionalLong expectedVersion, UUID actor);

  /**
   * Moves a location, and everything under it, somewhere else (REQ-CORE-043).
   *
   * <p>One operation and one event, whatever is inside: an item names the place it is in, and that
   * place has not changed — only where the place itself sits. The materialised paths below it are
   * rewritten in a single statement.
   *
   * <p>Three things can refuse it. A target inside the moved subtree would be a cycle, which is
   * what {@code REQ-CORE-045} forbids and what a path cannot represent. A target whose category has
   * declared what it takes, and did not name this one, is {@code REQ-CORE-047}. And a subtree whose
   * deepest place would land past the ceiling is {@link TooDeepException} — measured from the
   * deepest descendant, because moving a box moves everything in it.
   *
   * @param id the location to move
   * @param newParentId where to put it, or {@code null} to make it a root
   * @param expectedVersion the version the caller acted on, from the {@code ETag} of its last
   *     read; empty skips the check, which is what an internal caller with no screen to go
   *     stale passes (REQ-API-004)
   * @param actor the authenticated user
   * @return the location as it now stands
   * @throws de.greluc.homeinv.platform.NotFoundException when the tenant has no such live location,
   *     or no such target
   * @throws InvalidMoveException when the target is inside the subtree, or its category does not
   *     take this one
   * @throws TooDeepException when the subtree would exceed the depth limit
   * @throws NameTakenException when the target already has a child of this name
   */
  LocationView move(UUID id, UUID newParentId, OptionalLong expectedVersion, UUID actor);

  /**
   * Deletes a location, if nothing is inside it.
   *
   * @param id the location
   * @param expectedVersion the version the caller acted on, from the {@code ETag} of its last
   *     read; empty skips the check, which is what an internal caller with no screen to go
   *     stale passes (REQ-API-004)
   * @param actor the authenticated user
   * @throws LocationNotEmptyException when a place or an item is still inside it
   * @throws de.greluc.homeinv.platform.StaleVersionException when somebody else changed it first
   */
  void delete(UUID id, OptionalLong expectedVersion, UUID actor);

  /**
   * The ids of a location and everything below it (REQ-CORE-049).
   *
   * @param id the location at the top of the subtree
   * @return the ids, including the location itself
   */
  List<UUID> subtreeIds(UUID id);

  /**
   * Rolls counts per location up to the children of one place (REQ-SRCH-002).
   *
   * <p>The location facet. An item's {@code location_id} names the exact shelf it sits on, and a
   * sidebar that listed every shelf in the house would be a list nobody can use — what somebody
   * wants is "Schuppen 18, Küche 7", and then the same again one level down once they have clicked.
   * So the counts come in per location and go out per <b>direct child of {@code root}</b>, each
   * carrying everything beneath it.
   *
   * <p>This is the one facet that keeps its own filter rather than dropping it (decided with the
   * owner 2026-09-14): a tree is drilled into by descending, not by looking at siblings, so
   * {@code filter=location:subtree:X} sets the level the counts are taken at instead of being
   * removed from them.
   *
   * <p>Counts for the root itself, and for anything above it, are dropped: an item lying directly
   * in the shed is in none of the shed's children, and inventing a bucket for it would make the
   * numbers add up to more than the list.
   *
   * @param countsByLocation how many items sit in each location, from whoever counted them
   * @param root the place whose children are wanted, or {@code null} for the roots of the tree
   * @return the count per child location, summed over each child's whole subtree; empty when
   *     nothing falls under a child
   * @throws de.greluc.homeinv.platform.NotFoundException when {@code root} is not a location this
   *     tenant can see
   */
  Map<UUID, Long> rollUp(Map<UUID, Long> countsByLocation, UUID root);

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
  Page<LocationView> list(String cursor, int limit);

}
