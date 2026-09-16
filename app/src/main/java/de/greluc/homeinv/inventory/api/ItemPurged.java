/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.api;

import java.util.UUID;
import org.springframework.modulith.events.Externalized;

/**
 * An item was finally removed — the second stage of REQ-CORE-009, and the irreversible one.
 *
 * <p>Published inside the transaction that removes the row, and consumed inside it: whatever hangs
 * on an item and is not a foreign key into it has to go in the same commit, or a purge leaves
 * orphans that reference an id nothing answers for. {@code media.attachment} is such a thing — it is
 * polymorphic and therefore cannot have a foreign key at all (07 §7.8) — so {@code media} listens
 * and detaches.
 *
 * <p>The revision history is deliberately not among them. A removal that erased its own record would
 * leave nothing to say the thing ever existed, which is the one question a history exists to answer.
 *
 * @param tenantId the tenant the item belonged to; a consumer establishes its context from here
 * @param itemId the item that no longer exists
 */
@Externalized("homeinv.inventory::item-purged.v1")
public record ItemPurged(UUID tenantId, UUID itemId) {}
