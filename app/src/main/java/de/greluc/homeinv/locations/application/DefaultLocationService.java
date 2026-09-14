/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.locations.application;

import de.greluc.homeinv.platform.Page;
import de.greluc.homeinv.catalog.api.TypeRegistry;
import de.greluc.homeinv.idempotency.api.RequestKey;
import de.greluc.homeinv.inventory.api.ItemLocationUsage;
import de.greluc.homeinv.locations.api.InvalidMoveException;
import de.greluc.homeinv.locations.api.LocationMoved;
import de.greluc.homeinv.locations.api.LocationNotEmptyException;
import de.greluc.homeinv.locations.api.LocationService;
import de.greluc.homeinv.locations.api.LocationView;
import de.greluc.homeinv.locations.api.NameTakenException;
import de.greluc.homeinv.locations.api.TooDeepException;
import de.greluc.homeinv.locations.domain.Location;
import de.greluc.homeinv.locations.infrastructure.LocationRepository;
import de.greluc.homeinv.locations.infrastructure.LocationTreeQueries;
import de.greluc.homeinv.platform.CursorCodec;
import de.greluc.homeinv.platform.NotFoundException;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.platform.Versions;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The use cases for storage locations.
 *
 * <h2>The move, and what it costs the tree</h2>
 *
 * <p>Stage 0 had none, and {@code REQ-CORE-045}'s "cycles are impossible" was structural: a
 * location's parent was fixed at creation and a child's path was its parent's plus one label, so
 * the shape was a tree by construction. Moving is exactly what ends that, so the guarantee is now a
 * check — the target must not lie inside the subtree being moved, which is one {@code <@}
 * comparison, written where the move is and nowhere else.
 *
 * <p>Two more refuse a move. A category may say what it takes underneath it ({@code REQ-CORE-047}),
 * and a category that has said anything at all has said all of it: an empty rule set permits
 * everything, one entry permits that one. And the depth ceiling is measured on the <b>deepest
 * descendant</b> ({@code REQ-CORE-040}), because moving a box two levels down takes everything in
 * it two levels down as well.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DefaultLocationService implements LocationService {

  private final LocationRepository locations;

  /** Where a spent {@code Idempotency-Key} is looked up and recorded (REQ-API-005). */
  private final de.greluc.homeinv.idempotency.api.IdempotentRequests requests;

  /** Serialises what a key answered, so the repeat gets the same thing back. */
  private final tools.jackson.databind.ObjectMapper json;

  /** What a key is spent on, so one key cannot create a place and an item. */
  private static final String CREATE_LOCATION = "POST /api/v1/locations";
  private final LocationTreeQueries tree;
  /** Where {@code LocationMoved} goes (REQ-CORE-043). */
  private final ApplicationEventPublisher events;
  private final ItemLocationUsage itemUsage;
  // A location stores the category VERSION it was written against; a caller names
  // the category. Only `catalog` may turn one into the other, because the tables
  // that answer it belong to `catalog` and 04 §4.5 does not let this block read
  // them.
  private final TypeRegistry types;

  /** Seals what the category marks sensitive, and keeps what this caller was never shown. */
  private final de.greluc.homeinv.catalog.api.AttributeSealing sealing;

  /** The binding check of REQ-CORE-005, applied to a category's fields (REQ-CORE-041). */
  private final de.greluc.homeinv.catalog.api.AttributeValidator validator;
  /** Removes the sensitive attributes this caller may not read (REQ-TEN-008). */
  private final de.greluc.homeinv.catalog.api.AttributeRedaction redaction;

  /**
   * Answers whether a place lies in the part of the tree this session is confined to
   * (REQ-TEN-007).
   *
   * <p>12 §12.5 layer three: checked on the <em>loaded</em> object, using the {@code ltree} path. A
   * scope of null is the whole tenant, which is what a membership without one has.
   */
  private final de.greluc.homeinv.locations.api.LocationScope scope;

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
   * @throws NameTakenException when a live sibling already carries this name
   */
  @Transactional
  @Override
  public LocationView create(
      CreateLocationCommand command, Optional<RequestKey> idempotency, UUID actor) {
    UUID tenantId = TenantContext.require();
    UUID id = command.id() != null ? command.id() : UUID.randomUUID();
    Instant now = Instant.now(clock);

    // Inside this transaction, before anything is written: a spent key answers
    // what it answered before and creates nothing (REQ-API-005). The record and
    // the place it protects share one COMMIT (ADR-0009).
    Optional<String> answered =
        idempotency.flatMap(key -> requests.replay(CREATE_LOCATION, key.key(), key.requestHash()));
    if (answered.isPresent()) {
      return json.readValue(answered.get(), LocationView.class);
    }

    requireInScope(command.parentId());

    // Asked before the insert, so the caller gets a `409` naming the name rather
    // than the `500` a constraint violation surfaced as until 2026-09-12. The
    // index stays the truth — this is a race away from being wrong, and losing
    // that race still leaves the database refusing the row.
    requireNameFree(tenantId, command.parentId(), command.name(), null);

    // Resolved once, here, and stored: the location keeps the fields the category
    // declared at this moment even after the category moves on (REQ-CORE-025).
    UUID categoryVersionId = types.publishedCategoryVersion(command.categoryId());
    // Merge, validate, seal (ADR-0019). A place carries attributes like an item
    // does, and a category may mark one sensitive like a type may.
    String attributes =
        sealing.sealed(
            categoryVersionId,
            id,
            validated(
                categoryVersionId, sealing.merged(categoryVersionId, id, command.attributes(), null)));

    Location created;
    if (command.parentId() == null) {
      created =
          Location.createRoot(
              id, tenantId, categoryVersionId, command.name(), attributes, actor, now);
    } else {
      Location parent =
          locations
              .findLive(tenantId, command.parentId())
              .orElseThrow(() -> new NotFoundException("location", command.parentId()));
      created =
          Location.createChild(
              id, tenantId, categoryVersionId, parent, command.name(), attributes, actor, now);
    }

    locations.save(created);
    log.debug("Location {} created in tenant {}", id, tenantId);

    LocationView view = toView(created, Map.of(categoryVersionId, command.categoryId()));
    // After the work and before the commit, which is the ordering the decision
    // turns on: written first it could outlive work that failed, written after
    // the commit it could be lost while the place stayed.
    idempotency.ifPresent(
        key ->
            requests.remember(
                CREATE_LOCATION,
                key.key(),
                key.requestHash(),
                json.writeValueAsString(view),
                actor));
    return view;
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
    return toView(location, categoryOf(location));
  }

  /**
   * Renames a location.
   *
   * @param id the location
   * @param name the new name
   * @param actor the authenticated user
   * @return the renamed location
   * @throws NotFoundException when the tenant has no such live location
   * @throws NameTakenException when a live sibling already carries the new name
   */
  @Transactional
  @Override
  public LocationView rename(
      UUID id, String name, OptionalLong expectedVersion, UUID actor) {
    UUID tenantId = TenantContext.require();
    Location location =
        locations.findLive(tenantId, id).orElseThrow(() -> new NotFoundException("location", id));
    Versions.requireCurrent("location", id, expectedVersion, location.getVersion());
    // Excluding itself, so renaming "Cellar" to "cellar" is a rename and not a
    // conflict with the row being renamed.
    requireNameFree(tenantId, location.getParentId(), name, id);
    location.rename(name, actor, Instant.now(clock));
    return toView(location, categoryOf(location));
  }

  /**
   * Refuses a name a live sibling already has.
   *
   * @param tenantId the tenant
   * @param parentId the parent the siblings hang under, or {@code null} among the roots
   * @param name the proposed name
   * @param exclude the location being renamed, so it does not conflict with itself
   * @throws NameTakenException when the name is taken
   */
  private void requireNameFree(UUID tenantId, UUID parentId, String name, UUID exclude) {
    if (name != null && locations.siblingNameTaken(tenantId, parentId, name, exclude)) {
      throw new NameTakenException(name, parentId);
    }
  }

  /**
   * Moves a location and everything under it.
   *
   * @param id the location to move
   * @param newParentId where to put it, or null to make it a root
   * @param actor the authenticated user
   * @return the location as it now stands
   */
  @Transactional
  @Override
  public LocationView move(
      UUID id, UUID newParentId, OptionalLong expectedVersion, UUID actor) {
    UUID tenantId = TenantContext.require();
    Instant now = Instant.now(clock);

    Location location =
        locations.findLive(tenantId, id).orElseThrow(() -> new NotFoundException("location", id));
    Versions.requireCurrent("location", id, expectedVersion, location.getVersion());
    // Both ends: somebody confined to a subtree may not move a place out of it,
    // and may not move one in from outside (REQ-TEN-007).
    requireInScope(id);
    requireInScope(newParentId);

    if (Objects.equals(location.getParentId(), newParentId)) {
      // Already there. Not an error, for the reason deleting twice is not: a
      // client retrying a request whose answer it never saw.
      return toView(location, categoryOf(location));
    }

    Location target = null;
    if (newParentId != null) {
      target =
          locations
              .findLive(tenantId, newParentId)
              .orElseThrow(() -> new NotFoundException("location", newParentId));
      requireNotInsideItself(location, target);
      requireCategoryPermitted(location, target);
    }

    requireNameFree(tenantId, newParentId, location.getName(), id);

    // Measured on the deepest descendant, not on the location itself: moving a
    // box two levels down takes everything in it two levels down as well.
    int deepest = tree.deepestBelow(tenantId, id);
    int levelsMoved = (target == null ? 0 : target.getDepth() + 1) - location.getDepth();
    if (deepest + levelsMoved > Location.MAX_DEPTH) {
      throw new TooDeepException(newParentId, Location.MAX_DEPTH);
    }

    UUID fromParentId = location.getParentId();
    String oldPath = location.getPath();
    String newPath =
        target == null
            ? Location.labelOf(id)
            : target.getPath() + "." + Location.labelOf(id);

    location.movedTo(target, newPath, actor, now);
    // Flushed before the descendants are rewritten so that the two writes cannot
    // be reordered: Hibernate does not see a native statement and would otherwise
    // be free to hold this row until commit, leaving the subtree pointing at a
    // path its root no longer has. It is also what takes the moved row out of the
    // rewrite below, whose prefix it no longer matches.
    locations.flush();
    int followed = tree.rewriteSubtree(tenantId, id, oldPath, newPath, now, actor);

    // One event for the lot (REQ-CORE-043). The items inside are untouched by
    // construction: an item names the place it is in, and that place still is the
    // same place -- which is why moving a box with 200 items in it is one event
    // and not 201.
    events.publishEvent(new LocationMoved(tenantId, id, fromParentId, newParentId, followed + 1));
    log.info("Location {} moved in tenant {}; {} place(s) followed it.", id, tenantId, followed);
    return toView(location, categoryOf(location));
  }

  /**
   * Refuses a target that is the location itself or inside it.
   *
   * @param location the location being moved
   * @param target where it would go
   * @throws InvalidMoveException when that would be a cycle
   */
  private static void requireNotInsideItself(Location location, Location target) {
    // On the paths and not on a walk up the parents: the path is what a cycle
    // would corrupt, so the check is written in the same terms. The trailing
    // separator matters -- without it "ab" reads as a descendant of "a".
    String subtree = location.getPath() + ".";
    if (target.getId().equals(location.getId()) || (target.getPath() + ".").startsWith(subtree)) {
      throw new InvalidMoveException(
          "A place cannot be moved into itself or into something it contains.");
    }
  }

  /**
   * Refuses a target whose category does not take this one (REQ-CORE-047).
   *
   * @param location the location being moved
   * @param target where it would go
   */
  private void requireCategoryPermitted(Location location, Location target) {
    UUID childCategory = types.categoryOfVersion(location.getCategoryVersionId());
    UUID parentCategory = types.categoryOfVersion(target.getCategoryVersionId());
    if (!types.permitsChildCategory(parentCategory, childCategory)) {
      throw new InvalidMoveException(
          "That place does not take this kind of place. Its category permits only the ones it"
              + " names.");
    }
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
  public void delete(UUID id, OptionalLong expectedVersion, UUID actor) {
    UUID tenantId = TenantContext.require();
    Location location =
        locations.findLive(tenantId, id).orElseThrow(() -> new NotFoundException("location", id));
    Versions.requireCurrent("location", id, expectedVersion, location.getVersion());

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
  public Page<LocationView> list(String cursor, int limit) {
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

    // One lookup for the page rather than one per row: a full page carries two
    // hundred locations, and the category of each is a join this block may not make.
    Map<UUID, UUID> categories =
        types.categoriesOfVersions(rows.stream().map(Location::getCategoryVersionId).toList());
    List<LocationView> views = rows.stream().map(row -> toView(row, categories)).toList();

    // A cursor only when the page was full: a short page is the last one, and a
    // cursor for it would cost a client a request to discover that.
    String nextCursor = null;
    if (rows.size() == size) {
      Location last = rows.get(rows.size() - 1);
      nextCursor =
          cursors.encode(
              CursorCodec.Position.of(last.getCreatedAt(), last.getId()), CURSOR_FINGERPRINT);
    }
    return Page.of(views, nextCursor);
  }

  /**
   * Builds the published view, including the readable path.
   *
   * @param location the aggregate
   * @param categories version id to category id, covering at least this location's version
   * @return the view
   */
  /**
   * The category behind one location's version.
   *
   * <p>For the single-location paths. The listing resolves a page in one call instead, because two
   * hundred of these would be two hundred statements.
   *
   * @param location the location
   * @return a map with the one entry {@link #toView} needs
   */
  private Map<UUID, UUID> categoryOf(Location location) {
    return types.categoriesOfVersions(List.of(location.getCategoryVersionId()));
  }

  private LocationView toView(Location location, Map<UUID, UUID> categories) {
    return new LocationView(
        location.getId(),
        location.getName(),
        categories.get(location.getCategoryVersionId()),
        location.getParentId(),
        location.getDepth(),
        // On the way out, for the reason the item path gives: what is stored is
        // complete, and what a particular person is shown is a projection of it
        // (REQ-TEN-008).
        redaction.forCaller(
            location.getCategoryVersionId(), location.getId(), location.getAttributes()),
        tree.ancestorNames(location.getTenantId(), location.getId()),
        // The concurrency token, which is what a client sends back as `If-Match`.
        // In the view rather than derived from a hash of it: a hash changes when
        // a redacted field is added or removed for a caller, and two people with
        // different field permissions would then disagree about what the same
        // unchanged location's tag is (REQ-API-004).
        location.getVersion());
  }

  /**
   * Refuses a parent outside the part of the tree this session is confined to (REQ-TEN-007).
   *
   * <p>Layer three of 12 §12.5. The policy would refuse the write too, as a policy violation that
   * reaches a client as a {@code 500}; this turns it into the answer a place they may not see
   * deserves (REQ-SEC-025). A scoped session creating a root — no parent at all — is refused for
   * the same reason: a root is outside every subtree, its own included.
   *
   * @param parentId the parent being named, or null for a root
   * @throws NotFoundException when the session is scoped and the parent is elsewhere
   */
  private void requireInScope(UUID parentId) {
    UUID confinedTo =
        de.greluc.homeinv.platform.CallerContext.current()
            .map(de.greluc.homeinv.platform.CallerContext.Caller::scopeLocationId)
            .orElse(null);
    if (confinedTo != null && !scope.contains(confinedTo, parentId)) {
      throw new NotFoundException("location", parentId);
    }
  }

  /**
   * Checks an attribute set against a category version and hands back what to store.
   *
   * @param categoryVersionId the version the place is written against
   * @param attributes the set as JSON text, possibly {@code null}
   * @return the set to store, {@code {}} where the caller sent nothing
   * @throws de.greluc.homeinv.catalog.api.InvalidAttributesException when the set does not match
   */
  private String validated(UUID categoryVersionId, String attributes) {
    String candidate = attributes == null || attributes.isBlank() ? "{}" : attributes;
    var result = validator.validate(categoryVersionId, candidate);
    if (!result.valid()) {
      throw new de.greluc.homeinv.catalog.api.InvalidAttributesException(result.violations());
    }
    return candidate;
  }

}
