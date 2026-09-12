/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.locations.application;

import de.greluc.homeinv.inventory.api.ItemLocationUsage;
import de.greluc.homeinv.locations.api.LocationNotEmptyException;
import de.greluc.homeinv.locations.api.LocationService;
import de.greluc.homeinv.locations.api.LocationView;
import de.greluc.homeinv.locations.domain.Location;
import de.greluc.homeinv.locations.infrastructure.LocationRepository;
import de.greluc.homeinv.locations.infrastructure.LocationTreeQueries;
import de.greluc.homeinv.platform.CursorCodec;
import de.greluc.homeinv.platform.NotFoundException;
import de.greluc.homeinv.platform.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The use cases for storage locations.
 *
 * <h2>Why there is no move operation at stage 0</h2>
 *
 * <p>{@code REQ-CORE-045} requires cycles in the tree to be impossible, and at stage 0 they are —
 * provably, not by a check. A location's parent is fixed when it is created, and a child's path is
 * its parent's path plus one label, so the structure is a tree by construction. The only way to
 * create a cycle is to re-parent an existing subtree, and nothing here does that.
 *
 * <p>When moving arrives, the guarantee stops being structural and becomes a check: the target must
 * not be inside the subtree being moved, which is one {@code <@} comparison. That is a deliberate
 * note rather than an omission — the requirement is satisfied today for a reason that will expire.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DefaultLocationService implements LocationService {

  private final LocationRepository locations;
  private final LocationTreeQueries tree;
  private final ItemLocationUsage itemUsage;
  private final CursorCodec cursors;
  private final Clock clock;

  /** The cap REQ-NFR-010 puts on every page of every collection. */
  private static final int MAX_PAGE = 200;

  /**
   * What a location cursor is bound to.
   *
   * <p>A constant, because this listing takes no filter: there is one listing per tenant, and the
   * tenant is not part of the fingerprint because a cursor is worthless in another tenant's session
   * — the query it resumes is re-authorised and row-level security answers it.
   */
  private static final String CURSOR_FINGERPRINT = "locations";

  /**
   * Creates a location, as a root or below an existing one.
   *
   * @param command what to create
   * @param actor the authenticated user
   * @return the new location
   * @throws NotFoundException when a named parent is not visible to this tenant
   * @throws de.greluc.homeinv.locations.api.TooDeepException when the tree would exceed its
   *     depth limit
   */
  @Transactional
  @Override
  public LocationView create(CreateLocationCommand command, UUID actor) {
    UUID tenantId = TenantContext.require();
    UUID id = command.id() != null ? command.id() : UUID.randomUUID();
    Instant now = Instant.now(clock);

    Location created;
    if (command.parentId() == null) {
      created = Location.createRoot(id, tenantId, command.categoryId(), command.name(), actor, now);
    } else {
      Location parent =
          locations
              .findLive(tenantId, command.parentId())
              .orElseThrow(() -> new NotFoundException("location", command.parentId()));
      created =
          Location.createChild(
              id, tenantId, command.categoryId(), parent, command.name(), actor, now);
    }

    locations.save(created);
    log.debug("Location {} created in tenant {}", id, tenantId);
    return toView(created);
  }

  /**
   * Reads one location, with its readable path.
   *
   * @param id the location
   * @return the location and the names from the root down to it
   * @throws NotFoundException when the tenant has no such live location
   */
  @Transactional(readOnly = true)
  @Override
  public LocationView get(UUID id) {
    UUID tenantId = TenantContext.require();
    Location location =
        locations.findLive(tenantId, id).orElseThrow(() -> new NotFoundException("location", id));
    return toView(location);
  }

  /**
   * Renames a location.
   *
   * @param id the location
   * @param name the new name
   * @param actor the authenticated user
   * @return the renamed location
   * @throws NotFoundException when the tenant has no such live location
   */
  @Transactional
  @Override
  public LocationView rename(UUID id, String name, UUID actor) {
    UUID tenantId = TenantContext.require();
    Location location =
        locations.findLive(tenantId, id).orElseThrow(() -> new NotFoundException("location", id));
    location.rename(name, actor, Instant.now(clock));
    return toView(location);
  }

  /**
   * Deletes a location, leaving a tombstone.
   *
   * <p>Refused while anything is still inside it, whether a place or an item. Cascading would
   * delete things a person did not ask to delete; leaving the contents behind would orphan them,
   * and the tree cannot represent an orphan. Saying no is the only honest third option.
   *
   * @param id the location
   * @param actor the authenticated user
   * @throws NotFoundException when the tenant has no such live location
   * @throws LocationNotEmptyException when a place or an item is still inside it
   */
  @Transactional
  @Override
  public void delete(UUID id, UUID actor) {
    UUID tenantId = TenantContext.require();
    Location location =
        locations.findLive(tenantId, id).orElseThrow(() -> new NotFoundException("location", id));

    if (tree.hasChildren(tenantId, id)) {
      throw new LocationNotEmptyException(id, "it still contains other locations");
    }
    if (itemUsage.anyItemIn(id)) {
      throw new LocationNotEmptyException(id, "it still contains items");
    }

    location.markDeleted(actor, Instant.now(clock));
  }

  /**
   * The ids of a location and everything below it.
   *
   * <p>Serves "list the contents of this location, including the subtree" ({@code REQ-CORE-049}).
   * Returns ids rather than items, because the items belong to another block and this one has no
   * business holding them.
   *
   * @param id the location at the top of the subtree
   * @return the ids, including the location itself
   * @throws NotFoundException when the tenant has no such live location
   */
  @Transactional(readOnly = true)
  @Override
  public List<UUID> subtreeIds(UUID id) {
    UUID tenantId = TenantContext.require();
    locations.findLive(tenantId, id).orElseThrow(() -> new NotFoundException("location", id));
    return tree.subtreeIds(tenantId, id);
  }

  @Override
  @Transactional(readOnly = true)
  public LocationPage list(String cursor, int limit) {
    UUID tenantId = TenantContext.require();
    int size = Math.clamp(limit, 1, MAX_PAGE);

    List<Location> rows;
    if (cursor == null || cursor.isBlank()) {
      rows = locations.findLive(tenantId, PageRequest.of(0, size));
    } else {
      // Throws when the cursor was tampered with or belongs to another listing.
      CursorCodec.Position after = cursors.decode(cursor, CURSOR_FINGERPRINT);
      rows =
          locations.findLiveAfter(
              tenantId, after.createdAt(), after.id(), PageRequest.of(0, size));
    }

    List<LocationView> views = rows.stream().map(this::toView).toList();

    // A cursor only when the page was full: a short page is the last one, and a
    // cursor for it would cost a client a request to discover that.
    String nextCursor = null;
    if (rows.size() == size) {
      Location last = rows.get(rows.size() - 1);
      nextCursor =
          cursors.encode(
              new CursorCodec.Position(last.getCreatedAt(), last.getId()), CURSOR_FINGERPRINT);
    }
    return new LocationPage(views, nextCursor);
  }

  /**
   * Builds the published view, including the readable path.
   *
   * @param location the aggregate
   * @return the view
   */
  private LocationView toView(Location location) {
    return new LocationView(
        location.getId(),
        location.getName(),
        location.getCategoryId(),
        location.getParentId(),
        location.getDepth(),
        tree.ancestorNames(location.getTenantId(), location.getId()));
  }

}
