/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.locations.api;

import java.util.List;
import java.util.UUID;

/**
 * Whether something lies inside the part of the tree a membership is confined to (REQ-TEN-007).
 *
 * <p>12 §12.5 puts this at layer three — "object scope: checked on the loaded object … does it lie
 * in the permitted location subtree" — and says the check uses the {@code ltree} path. This block
 * owns that path, so it answers, and {@code inventory} asks rather than reaching into a foreign
 * schema.
 *
 * <p>The scope itself is stored as a location <b>id</b>, on {@code tenancy.membership}. A path in
 * that row would be a copy of something that moves: re-parenting a subtree rewrites every path
 * under it (07 §7.4), and the copy would be right until somebody moved the garage.
 */
public interface LocationScope {

  /**
   * Whether one location lies within a scope.
   *
   * <p>A scope contains itself: somebody confined to the garage may see the garage.
   *
   * @param scopeRootId the location the membership is confined to
   * @param locationId the location being reached, or null
   * @return {@code true} when the location is the scope or sits below it; {@code false} when it is
   *     elsewhere, when it does not exist, and when it is null — a thing with no place is not in
   *     anybody's garage
   */
  boolean contains(UUID scopeRootId, UUID locationId);

  /**
   * Every location id within a scope, for filtering a list.
   *
   * @param scopeRootId the location the membership is confined to
   * @return the ids, the scope's own included
   */
  List<UUID> idsWithin(UUID scopeRootId);
}
