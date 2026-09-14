/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.api;

import java.util.UUID;

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
public record ItemDeleted(UUID tenantId, UUID itemId) {}
