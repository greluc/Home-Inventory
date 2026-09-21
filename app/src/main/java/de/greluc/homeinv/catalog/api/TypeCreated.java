/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.catalog.api;

import de.greluc.homeinv.platform.EventType;
import de.greluc.homeinv.platform.TenantScopedEvent;
import java.util.UUID;

/**
 * A tenant defined a new item type or location category (04 §4.3).
 *
 * <p>In-process. The audit log is the consumer that matters — ADR-0020 requires a configuration
 * change to appear there with a before and an after — and the audit block runs in the same process
 * as the write it records.
 *
 * @param tenantId whose type system changed
 * @param ownerId the item type or the location category
 * @param key the stable key it was given
 * @param category whether it is a location category rather than an item type
 */
public record TypeCreated(UUID tenantId, UUID ownerId, String key, boolean category) implements TenantScopedEvent {

  /**
   * What happened.
   *
   * @return type.created
   */
  @Override
  public EventType eventType() {
    return EventType.TYPE_CREATED;
  }

  /**
   * What it happened to.
   *
   * @return the item type or location category that was defined
   */
  @Override
  public UUID subjectId() {
    return ownerId;
  }
}
