/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.locations.api;

import de.greluc.homeinv.platform.EventType;
import de.greluc.homeinv.platform.TenantScopedEvent;
import java.util.UUID;

/**
 * A location and everything under it now sit somewhere else (REQ-CORE-043).
 *
 * <p><b>One</b> event for the whole subtree, which is what the requirement asks for in as many
 * words: "moving a box with 200 items produces one event, not 200". That is not an optimisation
 * here but the shape of the data — an item names the location it is in, and the location's own
 * place changed, so nothing about the items changed at all. The paths of the places <em>below</em>
 * the moved one are rewritten in a single statement, for the same reason.
 *
 * @param tenantId the tenant; a consumer establishes its context from here
 * @param locationId the location that moved
 * @param fromParentId where it was, or null when it was a root
 * @param toParentId where it is, or null when it is now a root
 * @param subtreeSize how many places moved with it, itself included — the number a client shows
 *     when it says what just happened
 */
public record LocationMoved(
    UUID tenantId, UUID locationId, UUID fromParentId, UUID toParentId, int subtreeSize) implements TenantScopedEvent {

  /**
   * What happened.
   *
   * @return location.moved
   */
  @Override
  public EventType eventType() {
    return EventType.LOCATION_MOVED;
  }

  /**
   * What it happened to.
   *
   * @return the location that was moved
   */
  @Override
  public UUID subjectId() {
    return locationId;
  }
}
