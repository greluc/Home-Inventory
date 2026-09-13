/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.api;

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
   * Removes a relation.
   *
   * <p>Idempotent for the same reason {@link #relate} is.
   *
   * @param relationId the relation
   * @param actor the authenticated user
   */
  void unrelate(UUID relationId, UUID actor);

  /**
   * Every relation one item takes part in, from either end.
   *
   * @param itemId the item
   * @param cursor an opaque cursor from a previous page, or {@code null} for the first
   * @param limit how many at most; capped at 200
   * @return the page and a cursor for the next one
   */
  RelationPage relationsOf(UUID itemId, String cursor, int limit);

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

  /**
   * One page of relations.
   *
   * @param items the relations on this page
   * @param nextCursor the cursor for the next page, or {@code null} when this was the last
   */
  record RelationPage(List<RelationView> items, String nextCursor) {}
}
