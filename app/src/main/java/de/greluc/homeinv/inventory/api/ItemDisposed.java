/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.api;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.modulith.events.Externalized;

/**
 * An item left the inventory for good (REQ-LIFE-007).
 *
 * <p>Sold or disposed of — {@link #state} says which, because a consumer reporting on what a
 * household parted with cares about the difference and should not have to read the item back to
 * learn it. Carries identifiers and the scalars a consumer branches on and never the item itself,
 * because an entity does not leave its block (REQ-NFR-022).
 *
 * <p>Distinct from {@link ItemDeleted}, and the distinction is the point: a deletion says the
 * <i>record</i> is going, a disposal says the <i>thing</i> went and the record stays.
 *
 * @param tenantId whose item
 * @param itemId which item
 * @param state {@link ItemState#SOLD} or {@link ItemState#DISPOSED}
 * @param on when it went
 */
@Externalized("homeinv.inventory::item-disposed.v1")
public record ItemDisposed(UUID tenantId, UUID itemId, ItemState state, LocalDate on) {}
