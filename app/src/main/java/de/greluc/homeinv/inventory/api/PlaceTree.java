/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.api;

import java.util.List;
import java.util.UUID;

/**
 * The shape of the location tree, for a report that has to roll figures up it (REQ-LIFE-008).
 *
 * <p>Declared <b>here</b> and implemented by {@code locations}, for {@link PlaceScope}'s reason and
 * not a new one: {@code locations} already depends on this block — it asks whether a place still
 * holds anything before letting it be deleted — so a call from here to {@code LocationService}
 * closes a cycle. The module check fails the build on one, and did on 2026-09-20 when the valuation
 * report first reached for it.
 *
 * <p>Two methods and no more. A report needs to know what is above a place, to add a room's
 * contents into the house; and what is beneath one, to answer for a subtree only. It does not need
 * names, categories, or anything else the tree carries — {@link #labelOf} exists because a row has
 * to be readable, and it answers with a name rather than with a location.
 */
public interface PlaceTree {

  /**
   * A place and everything above it, root first and the place itself last.
   *
   * <p>The "itself last" matters and is easy to get wrong: a caller that adds the place a second
   * time counts everything directly in it twice, which is what the first draft of the valuation
   * report did.
   *
   * @param locationId the place
   * @return the ids from the root down to it, itself last; empty when this tenant has no such live
   *     place
   */
  List<UUID> ancestorsOf(UUID locationId);

  /**
   * A place and everything beneath it.
   *
   * @param locationId the place
   * @return the ids of the subtree, including the place itself
   * @throws de.greluc.homeinv.platform.NotFoundException when this tenant has no such place
   */
  List<UUID> subtreeOf(UUID locationId);

  /**
   * What to call a place in a report row.
   *
   * @param locationId the place
   * @return its name, or {@code null} when it is no longer there — a row that lost its label still
   *     says something true about money, and dropping it would make the totals above it stop
   *     adding up
   */
  String labelOf(UUID locationId);
}
