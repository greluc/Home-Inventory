/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.api;

import java.math.BigDecimal;
import java.util.List;
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
   * @param actor the authenticated user, recorded in the audit columns
   * @return the item and whether this call created it, which decides 201 versus 200
   * @throws ItemAlreadyExistsException when the id exists in this tenant with different content
   */
  CreateResult create(CreateItemCommand command, UUID actor);

  /**
   * Reads one item.
   *
   * @param id the item
   * @return the item as the published view
   * @throws de.greluc.homeinv.platform.NotFoundException when the tenant has no such live item. An
   *     item of another tenant fails the same way as one that never existed (REQ-SEC-016)
   */
  ItemView get(UUID id);

  /**
   * Changes an item.
   *
   * @param id the item
   * @param command the new values
   * @param actor the authenticated user
   * @return the changed item
   */
  ItemView update(UUID id, UpdateItemCommand command, UUID actor);

  /**
   * Deletes an item, leaving a tombstone.
   *
   * <p>Idempotent: deleting an already deleted item succeeds quietly, because a client retrying a
   * request whose answer it never saw must not be told it failed for succeeding twice.
   *
   * @param id the item
   * @param actor the authenticated user
   */
  void delete(UUID id, UUID actor);

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
   * @param actor the authenticated user
   * @return the item, active again
   * @throws de.greluc.homeinv.platform.NotFoundException when the tenant never had such an item
   * @throws IllegalStateException when it is physical and the place it was in has since been removed
   */
  ItemView restore(UUID id, UUID actor);

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
   * @param actor the authenticated user
   * @throws de.greluc.homeinv.platform.NotFoundException when the tenant never had such an item
   * @throws IllegalStateException when it is not in the trash
   */
  void purge(UUID id, UUID actor);

  /**
   * One page of the tenant's trashed items, newest first.
   *
   * @param cursor an opaque cursor from a previous page, or {@code null} for the first
   * @param limit how many at most; capped at 200
   * @return the page and a cursor for the next one
   */
  ItemPage trashed(String cursor, int limit);

  /**
   * One page of an item's history, newest first (REQ-CORE-010).
   *
   * @param id the item
   * @param cursor an opaque cursor from a previous page, or {@code null} for the first
   * @param limit how many at most; capped at 200
   * @return the page and a cursor for the next one
   * @throws de.greluc.homeinv.platform.NotFoundException when the tenant never had such an item
   */
  de.greluc.homeinv.audit.api.RevisionLog.RevisionPage history(UUID id, String cursor, int limit);

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
   * @param actor the authenticated user
   * @return the item in its restored state
   * @throws de.greluc.homeinv.platform.NotFoundException when the item or the revision is not
   *     visible to this tenant
   * @throws de.greluc.homeinv.catalog.api.InvalidAttributesException when the old attribute set no
   *     longer matches the item's type version
   */
  ItemView restoreRevision(UUID id, long revision, UUID actor);

  /**
   * One page of items.
   *
   * @param items the items on this page
   * @param nextCursor the cursor for the next page, or {@code null} when this was the last
   */
  record ItemPage(List<ItemView> items, String nextCursor) {}

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
      String attributes) {}

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
   */
  record UpdateItemCommand(
      String name,
      String description,
      UUID locationId,
      BigDecimal quantity,
      String quantityUnit,
      String attributes) {}
}
