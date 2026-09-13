/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A consumable has fallen below the level it is restocked at (REQ-CORE-008).
 *
 * <p>Raised on the write that takes it under, and not on every write while it stays under: "falling
 * below" is a crossing. A reminder that fired on each edit of a thing already known to be low would
 * be a reminder people turn off.
 *
 * @param tenantId whose inventory; a consumer establishes its context from here
 * @param itemId which consumable
 * @param quantity how many are left
 * @param minimumStock the level it fell below, so a consumer need not read the item back to say by
 *     how much
 */
public record StockBelowMinimum(
    UUID tenantId, UUID itemId, BigDecimal quantity, BigDecimal minimumStock) {}
