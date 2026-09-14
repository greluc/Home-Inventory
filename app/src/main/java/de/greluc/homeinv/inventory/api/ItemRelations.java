/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.api;

import de.greluc.homeinv.platform.Page;
import java.util.List;
import java.util.UUID;

/**
 * Relations between items (REQ-CORE-006).
 *
 * <p>Directed and read from both ends. "The lens is an accessory of the camera" is one row; the
 * camera's page shows it under accessories and the lens's page as what it is an accessory of, which
 * is the same row read the other way round. Storing both directions would be two rows that can
 * disagree.
 */
public interface ItemRelations {

  /**
   * Relates one item to another.
   *
   * <p>Idempotent: the same relation asked for twice is the first one, because a client retrying a
   * request it never saw the answer to must not be told it failed.
   *
   * @param sourceId the item the relation is stated from
   * @param targetId the item it points at
   * @param type what kind of relation
   * @param actor the authenticated user
   * @return the relation
   * @throws de.greluc.homeinv.platform.NotFoundException when either item is not visible
   * @throws IllegalArgumentException when the two are the same item — nothing is an accessory of
   *     itself
   */
  RelationView relate(UUID sourceId, UUID targetId, RelationType type, UUID actor);

  /**
   * Removes a relation from one of the two items it joins.
   *
   * <p>Idempotent about the <em>relation</em>, for the same reason {@link #relate} is: removing one
   * that is not there succeeds. It is not idempotent about the <b>item</b> — an item this tenant
   * cannot see is a {@code 404}, because the endpoint that calls this names the item in its path and
   * has to answer about it (REQ-SEC-025).
   *
   * <p>{@code itemId} is checked rather than ignored, which it was until 2026-09-14: the relation's
   * own id was enough to find the row, so the item in the path said nothing and a caller could name
   * any item at all. A relation that does not join this item is left alone.
   *
   * @param itemId the item the relation is being removed from
   * @param relationId the relation
   * @param actor the authenticated user
   * @throws de.greluc.homeinv.platform.NotFoundException when this tenant has no such item
   */
  void unrelate(UUID itemId, UUID relationId, UUID actor);

  /**
   * Every relation one item takes part in, from either end.
   *
   * @param itemId the item
   * @param cursor an opaque cursor from a previous page, or {@code null} for the first
   * @param limit how many at most; capped at 200
   * @return the page and a cursor for the next one
   */
  Page<ItemRelations.RelationView> relationsOf(UUID itemId, String cursor, int limit);

  /** The four kinds REQ-CORE-006 names. */
  enum RelationType {
    /** The source is an accessory of the target — a lens of a camera. */
    ACCESSORY_OF,
    /** The source is a part of the target — a wheel of a bicycle. */
    PART_OF,
    /** The source replaces the target — the new kettle for the one that broke. */
    REPLACEMENT_FOR,
    /** The two belong together and none of the above says how. */
    RELATED
  }

  /**
   * One relation, as the item at either end sees it.
   *
   * @param id the relation
   * @param sourceId the item the relation is stated from
   * @param targetId the item it points at
   * @param type what kind of relation
   * @param inbound whether the item that was asked is the TARGET rather than the source. A client
   *     renders "accessories" for one and "accessory of" for the other, from the same row
   */
  record RelationView(UUID id, UUID sourceId, UUID targetId, RelationType type, boolean inbound) {}

}
