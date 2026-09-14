/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.api;

import de.greluc.homeinv.platform.Page;
import java.util.List;
import java.util.UUID;

/**
 * Items that contain other items (REQ-CORE-007).
 *
 * <h2>A bundle is not a place</h2>
 *
 * <p>The requirement puts it in as many words: an item can be a bundle "containing other items
 * <b>without removing them from their location</b>". The camera bag contains the lens; the lens is
 * still on the shelf in the study, which is where somebody looking for it will go. Nothing here
 * writes a location.
 *
 * <h2>Many bundles, not one</h2>
 *
 * <p>An item may be in several bundles at once — in the camera bag and in the "insured equipment"
 * set — so what this describes is a directed acyclic graph and not a second tree. Forcing a choice
 * would make one of the two lists wrong about the same object.
 *
 * <p>That is what makes the cycle rule a walk rather than a comparison. With one parent per item a
 * cycle is a prefix question, the way it is for the location tree; with several it is reachability,
 * and adding a member is refused when the bundle can already be reached <em>from</em> that member.
 */
public interface ItemBundles {

  /**
   * Puts an item into a bundle.
   *
   * <p>Idempotent: the same membership asked for twice is the first one, because a client retrying
   * a request whose answer it never saw must not be told it failed.
   *
   * @param bundleId the item that contains
   * @param memberId the item that goes into it
   * @param actor the authenticated user
   * @return the membership
   * @throws de.greluc.homeinv.platform.NotFoundException when either item is not visible to this
   *     tenant
   * @throws BundleCycleException when the bundle already sits inside the member, directly or through
   *     any chain of bundles — including the case where the two are the same item
   */
  BundleMemberView add(UUID bundleId, UUID memberId, UUID actor);

  /**
   * Takes an item out of a bundle.
   *
   * <p>Idempotent for the reason {@link #add} is, and it moves nothing: the item was never anywhere
   * else than where it is kept.
   *
   * @param bundleId the item that contains
   * @param memberId the item to take out
   * @param actor the authenticated user
   */
  void remove(UUID bundleId, UUID memberId, UUID actor);

  /**
   * What is in a bundle, one page at a time.
   *
   * <p>Direct members only. A bundle inside a bundle is one row here and answers for its own
   * contents when it is asked, which is what keeps a page bounded no matter how deep the graph goes.
   * Members in the trash are left out: they are restorable, and a list of what is in a box should
   * not offer something that is not.
   *
   * @param bundleId the item that contains
   * @param cursor an opaque cursor from a previous page, or {@code null} for the first
   * @param limit how many at most; capped at 200
   * @return the page and a cursor for the next one
   */
  Page<ItemBundles.BundleMemberView> contentsOf(UUID bundleId, String cursor, int limit);

  /**
   * Which bundles an item is in, one page at a time.
   *
   * <p>The other direction, and not a rarer question: an item may be in several bundles, so "what
   * is this part of" has a list for an answer rather than a value.
   *
   * @param memberId the item
   * @param cursor an opaque cursor from a previous page, or {@code null} for the first
   * @param limit how many at most; capped at 200
   * @return the page and a cursor for the next one
   */
  Page<ItemBundles.BundleMemberView> bundlesOf(UUID memberId, String cursor, int limit);

  /**
   * One membership.
   *
   * @param id the membership, which is what a sync reconciles
   * @param bundleId the item that contains
   * @param memberId the item in it
   */
  record BundleMemberView(UUID id, UUID bundleId, UUID memberId) {}

}
