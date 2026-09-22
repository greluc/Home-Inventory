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
 * An item was taken back out of the trash (REQ-CORE-009, REQ-SRCH-005).
 *
 * <p>The undo of {@link ItemDeleted}, and a consumer has to treat it as a rebuild rather than a
 * flag: everything derived was thrown away when the item was trashed and has to come back.
 *
 * <p>Delivery is at least once; consumers are idempotent (04 §4.4).
 *
 * @param tenantId whose data changed
 * @param itemId the item
 */
@Externalized("homeinv.inventory::item-restored.v1")
public record ItemRestored(UUID tenantId, UUID itemId) implements TenantScopedEvent {

  /**
   * What happened.
   *
   * @return item.restored
   */
  @Override
  public EventType eventType() {
    return EventType.ITEM_RESTORED;
  }

  /**
   * What it happened to.
   *
   * @return the item that came back
   */
  @Override
  public UUID subjectId() {
    return itemId;
  }
}
