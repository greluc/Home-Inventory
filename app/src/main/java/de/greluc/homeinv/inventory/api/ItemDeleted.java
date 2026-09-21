/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.api;

import de.greluc.homeinv.platform.EventType;
import de.greluc.homeinv.platform.TenantScopedEvent;
import java.util.UUID;
import org.springframework.modulith.events.Externalized;

/**
 * An item was moved to the trash (REQ-CORE-009, REQ-SRCH-005).
 *
 * <p>The first of the two stages. The row is still there and can be restored for the whole
 * retention period, but it must stop being findable at once — a trashed thing that still answers a
 * search is a deletion that did not happen as far as anybody can tell (REQ-CORE-013). The second
 * stage is {@link ItemPurged}.
 *
 * <p>Delivery is at least once; consumers are idempotent (04 §4.4).
 *
 * @param tenantId whose data changed
 * @param itemId the item
 */
@Externalized("homeinv.inventory::item-deleted.v1")
public record ItemDeleted(UUID tenantId, UUID itemId) implements TenantScopedEvent {

  /**
   * What happened.
   *
   * @return item.deleted
   */
  @Override
  public EventType eventType() {
    return EventType.ITEM_DELETED;
  }

  /**
   * What it happened to.
   *
   * @return the item that went to the bin
   */
  @Override
  public UUID subjectId() {
    return itemId;
  }
}
