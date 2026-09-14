/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.api;

import de.greluc.homeinv.platform.Page;
import java.math.BigDecimal;
import de.greluc.homeinv.idempotency.api.RequestKey;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * What can be done with items.
 *
 * <p>An interface in the published package with the implementation behind it, because the access
 * layer calls it and a caller outside the block must not reach into {@code application}. That is
 * not ceremony: the modularity test reported exactly this, and the alternative — exposing the
 * whole {@code application} package — would publish the transaction boundaries and the internal
 * collaborators along with the use cases.
 */
public interface ItemService {

  /**
   * Creates an item, or returns the one that is already there.
   *
   * <p>The id may come from the client, because an offline client creates items without asking
   * (ADR-0016). A repeat of a creation whose answer the client never saw is therefore not an error:
   * the same id with the <em>same</em> content returns the existing item and says so, which is what
   * lets a client retry safely while {@code Idempotency-Key} is still stage 1 (REQ-API-005).
   *
   * @param command what to create
   * @param idempotency the {@code Idempotency-Key} the caller sent and the hash of what it
   *     sent with it, or empty when it sent none. A repeat carrying a spent key answers what
   *     that key answered before and creates nothing (REQ-API-005)
   * @param actor the authenticated user, recorded in the audit columns
   * @return the item and whether this call created it, which decides 201 versus 200
   * @throws ItemAlreadyExistsException when the id exists in this tenant with different content
   */
  CreateResult create(
      CreateItemCommand command, Optional<RequestKey> idempotency, UUID actor);

  /**
   * Reads one item.
   *
   * @param id the item
   * @return the item as the published view
   * @throws de.greluc.homeinv.platform.NotFoundException when the tenant has no such live item. An
   *     item of another tenant fails the same way as one that never existed (REQ-SEC-025)
   */
  ItemView get(UUID id);

  /**
   * Changes an item.
   *
   * @param id the item
   * @param command the new values
   * @param expectedVersion the version the caller acted on, from the {@code ETag} of its last
   *     read; empty skips the check, which is what an internal caller with no screen to go
   *     stale passes (REQ-API-004)
   * @param actor the authenticated user
   * @return the changed item
   * @throws de.greluc.homeinv.platform.StaleVersionException when somebody else changed it first
   */
  ItemView update(
      UUID id, UpdateItemCommand command, OptionalLong expectedVersion, UUID actor);

  /**
   * Reads many items at once, in the order they were asked for.
   *
   * <p>What a search needs: the index answers with ids (REQ-SRCH-007) and this turns them back into
   * rows — through row-level security and the field visibility rules, like every other read, which
   * is what makes a derived index safe to run at all.
   *
   * <p>An id this tenant cannot see is left out rather than refused. A stale index naming a row that
   * has since gone produces a shorter page, never an error and never somebody else's row.
   *
   * @param ids the items, in the order the answer should keep
   * @return the items that are visible, in that order
   */
  java.util.List<ItemView> byIds(java.util.List<UUID> ids);

  /**
   * Puts an item in another place.
   *
   * <p>A use case of its own rather than an {@link #update} carrying the other eight fields
   * unchanged. A caller that has to resend a name in order to move something is a caller that can
   * overwrite a name it never meant to touch, and a bulk move (REQ-CORE-011) has no name to resend
   * in the first place.
   *
   * <p>There is no REST path of its own: moving one item is an ordinary {@code PUT}, and this is
   * what {@code POST /api/v1/items/bulk} calls per entry.
   *
   * @param id the item
   * @param locationId where it goes; must be a place this session may see
   * @param expectedVersion the version the caller acted on; empty skips the check (REQ-API-004)
   * @param actor the authenticated user
   * @return the item, in its new place
   * @throws de.greluc.homeinv.platform.NotFoundException when the tenant has no such live item, or
   *     when the place is outside the subtree this session is confined to (REQ-TEN-007)
   * @throws IllegalArgumentException when the item is digital, which has no place to be in
   * @throws de.greluc.homeinv.platform.StaleVersionException when somebody else changed it first
   */
  ItemView move(UUID id, UUID locationId, OptionalLong expectedVersion, UUID actor);

  /**
   * Writes an item against another type (REQ-CORE-011).
   *
   * <p>The TYPE and not one of its versions: the version published today is the server's to
   * resolve, exactly as on creation (REQ-CORE-025). An item already on that type is left alone and
   * the call succeeds, so a selection that mixes types can be given one type in a single pass.
   *
   * <p>What happens to the attributes was decided with the owner on 2026-09-13: a value whose key
   * the new type declares <b>with the same data type</b> is carried over, and everything else is
   * dropped. Nothing is lost by it — the revision the change writes holds the whole earlier set,
   * and 04 §4.2 says a revision is what makes that true. A carried-over value the new type refuses
   * fails the call instead, because a caller who is told "invalid" can fix it and a caller whose
   * value silently vanished cannot.
   *
   * @param id the item
   * @param itemTypeId the type it is to be written against
   * @param expectedVersion the version the caller acted on; empty skips the check (REQ-API-004)
   * @param actor the authenticated user
   * @return the item, on its new type version
   * @throws de.greluc.homeinv.platform.NotFoundException when the tenant has no such live item, or
   *     no such type
   * @throws de.greluc.homeinv.catalog.api.InvalidAttributesException when what is carried over does
   *     not satisfy the new version
   * @throws de.greluc.homeinv.platform.StaleVersionException when somebody else changed it first
   */
  ItemView changeType(UUID id, UUID itemTypeId, OptionalLong expectedVersion, UUID actor);

  /**
   * Deletes an item, leaving a tombstone.
   *
   * <p>Idempotent: deleting an already deleted item succeeds quietly, because a client retrying a
   * request whose answer it never saw must not be told it failed for succeeding twice.
   *
   * @param id the item
   * @param expectedVersion the version the caller acted on, from the {@code ETag} of its last
   *     read; empty skips the check, which is what an internal caller with no screen to go
   *     stale passes (REQ-API-004)
   * @param actor the authenticated user
   * @throws de.greluc.homeinv.platform.StaleVersionException when somebody else changed it first
   */
  void delete(UUID id, OptionalLong expectedVersion, UUID actor);

  /**
   * The outcome of a creation.
   *
   * @param item the item, whether just created or found already present
   * @param created {@code true} when this call created it
   */
  record CreateResult(ItemView item, boolean created) {}

  /**
   * Brings an item back out of the trash (REQ-CORE-009).
   *
   * <p>Idempotent: restoring something that is not in the trash succeeds and changes nothing, so a
   * client retrying a request it never saw the answer to is not told it failed.
   *
   * @param id the item
   * @param expectedVersion the version the caller acted on, from the {@code ETag} of its last
   *     read; empty skips the check, which is what an internal caller with no screen to go
   *     stale passes (REQ-API-004)
   * @param actor the authenticated user
   * @return the item, active again
   * @throws de.greluc.homeinv.platform.NotFoundException when the tenant never had such an item
   * @throws IllegalStateException when it is physical and the place it was in has since been removed
   */
  ItemView restore(UUID id, OptionalLong expectedVersion, UUID actor);

  /**
   * Removes an item for good — the second stage of REQ-CORE-009.
   *
   * <p>The one irreversible operation on an item, which is why it is a permission of its own and why
   * it is refused for anything that is not in the trash: a person purges what they have already
   * decided to delete, not what they are looking at.
   *
   * <p>The revision history stays. It is what says the thing ever existed, and a removal that erased
   * its own record would leave nobody able to answer that.
   *
   * @param id the item, which must already be in the trash
   * @param expectedVersion the version the caller acted on, from the {@code ETag} of its last
   *     read; empty skips the check, which is what an internal caller with no screen to go
   *     stale passes (REQ-API-004)
   * @param actor the authenticated user
   * @throws de.greluc.homeinv.platform.NotFoundException when the tenant never had such an item
   * @throws IllegalStateException when it is not in the trash
   */
  void purge(UUID id, OptionalLong expectedVersion, UUID actor);

  /**
   * One page of the tenant's trashed items, newest first.
   *
   * @param cursor an opaque cursor from a previous page, or {@code null} for the first
   * @param limit how many at most; capped at 200
   * @return the page and a cursor for the next one
   */
  Page<ItemView> trashed(String cursor, int limit);

  /**
   * One page of an item's history, newest first (REQ-CORE-010).
   *
   * @param id the item
   * @param cursor an opaque cursor from a previous page, or {@code null} for the first
   * @param limit how many at most; capped at 200
   * @return the page and a cursor for the next one
   * @throws de.greluc.homeinv.platform.NotFoundException when the tenant never had such an item
   */
  Page<de.greluc.homeinv.audit.api.RevisionLog.RevisionView> history(
      UUID id, String cursor, int limit);

  /**
   * Makes an earlier state current again (REQ-CORE-010).
   *
   * <p>A restore is an ordinary change: it produces a new revision rather than rewinding to an old
   * one, so the history after it still says what happened and when. What it puts back are the fields
   * a person edits — name, description, place, quantity and attributes — and not the type version,
   * because an item does not travel between versions.
   *
   * @param id the item
   * @param revision which revision to put back
   * @param expectedVersion the version the caller acted on, from the {@code ETag} of its last
   *     read; empty skips the check, which is what an internal caller with no screen to go
   *     stale passes (REQ-API-004)
   * @param actor the authenticated user
   * @return the item in its restored state
   * @throws de.greluc.homeinv.platform.NotFoundException when the item or the revision is not
   *     visible to this tenant
   * @throws de.greluc.homeinv.catalog.api.InvalidAttributesException when the old attribute set no
   *     longer matches the item's type version
   */
  ItemView restoreRevision(
      UUID id, long revision, OptionalLong expectedVersion, UUID actor);


  /**
   * What is needed to create an item.
   *
   * @param id the client's chosen id, or {@code null} to have one generated
   * @param itemTypeId the type, or {@code null} to use the tenant's built-in one. The TYPE and not
   *     one of its versions: a person picks "book", and the version that is published today is the
   *     server's to resolve and the item's to keep (REQ-CORE-025)
   * @param name the name; must not be blank
   * @param description free text, may be {@code null}
   * @param kind physical or digital
   * @param locationId required for a physical item
   * @param quantity how many; {@code null} means one
   * @param quantityUnit the unit, may be {@code null}
   * @param attributes the fields the type declares, as JSON text; {@code null} means none. Checked
   *     against the version's generated schema before anything is written (REQ-CORE-005)
   * @param notes a paragraph about this thing, in limited Markdown; HTML is removed server-side
   *     (REQ-CORE-014)
   * @param minimumStock the level below which it needs restocking, or {@code null}. An item that
   *     carries one is a consumable (REQ-CORE-008)
   * @param valuation what it cost, what covers it and what replacing it would cost
   *     (REQ-LIFE-001/002/014). Replaced whole: a figure left out is one deliberately
   *     cleared, which is the same rule the attributes follow and the only one under which
   *     "remove the purchase price" is sayable at all
   */
  record CreateItemCommand(
      UUID id,
      UUID itemTypeId,
      String name,
      String description,
      ItemKind kind,
      UUID locationId,
      BigDecimal quantity,
      String quantityUnit,
      String attributes,
      String notes,
      BigDecimal minimumStock,
      Valuation valuation) {}

  /**
   * What may be changed about an item. {@code kind} is absent: a physical item does not become a
   * digital one, and allowing it would mean a location to drop and an invariant to check in two
   * directions.
   *
   * @param name the new name; must not be blank
   * @param description the new description, may be {@code null}
   * @param locationId the new location; required while the item is physical
   * @param quantity the new quantity; {@code null} means one
   * @param quantityUnit the new unit, may be {@code null}
   * @param notes the new notes; HTML is removed server-side
   * @param minimumStock the new restocking level, or {@code null} to stop tracking one
   * @param valuation what it cost, what covers it and what replacing it would cost
   *     (REQ-LIFE-001/002/014); replaced whole, so a figure left out is one cleared
   */
  record UpdateItemCommand(
      String name,
      String description,
      UUID locationId,
      BigDecimal quantity,
      String quantityUnit,
      String attributes,
      String notes,
      BigDecimal minimumStock,
      Valuation valuation) {}
}
