/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.api;

import java.util.UUID;
import org.springframework.modulith.events.Externalized;

/**
 * An item was edited (REQ-SRCH-005).
 *
 * <p>Name, description, notes, quantity or attributes — the ordinary edit. A move and a change of
 * type each have an event of their own, because they are different things to react to even though
 * the same endpoint can do them.
 *
 * <p>Delivery is at least once; consumers are idempotent (04 §4.4).
 *
 * @param tenantId whose data changed
 * @param itemId the item
 * @param name what it is called now
 * @param attributesChanged whether the attribute set is not the one it had. A consumer that
 *     mirrors attributes can skip a rename with this; one that mirrors the name cannot skip
 *     anything, which is why there is no flag for the other direction
 */
@Externalized("homeinv.inventory::item-updated")
public record ItemUpdated(
    UUID tenantId, UUID itemId, String name, boolean attributesChanged) {}
