/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.api;

import java.util.UUID;
import org.springframework.modulith.events.Externalized;

/**
 * An item was created (REQ-SRCH-005).
 *
 * <p>One of the six an item's life is made of, beside {@link ItemUpdated}, {@link ItemMoved},
 * {@link ItemTypeChanged}, {@link ItemDeleted}, {@link ItemRestored} and the final
 * {@link ItemPurged}. Separate records rather than one "something changed", decided with the owner
 * on 2026-09-14: a consumer that only cares where things are should not have to re-read every item
 * that was renamed, and a notification about a move is not a notification about an edit.
 *
 * <p>Carries identifiers and the scalars a consumer is likely to branch on, never the item itself.
 * An entity does not leave its block (REQ-NFR-022), and a search document is assembled by reading
 * the row again — the event says <i>that</i> something happened, not everything about it.
 *
 * <p>Delivery is at least once: Spring Modulith writes the publication to
 * {@code outbox.event_publication} before the broker sees it and redelivers after an outage, so
 * every consumer has to be idempotent (04 §4.4).
 *
 * @param tenantId whose data changed
 * @param itemId the item
 * @param itemTypeVersionId the type version it was written against
 * @param name what it is called
 * @param locationId where it is, or {@code null} for a digital item, which is nowhere
 */
@Externalized("homeinv.inventory::item-created.v1")
public record ItemCreated(
    UUID tenantId, UUID itemId, UUID itemTypeVersionId, String name, UUID locationId) {}
