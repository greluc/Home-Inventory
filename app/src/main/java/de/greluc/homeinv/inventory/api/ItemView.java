/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * What other blocks and the REST layer are allowed to see of an item.
 *
 * <p>This is the published type of the {@code inventory} block (REQ-NFR-023). The entity stays
 * inside: handed out, it carries its persistence context with it, so a caller could modify the
 * aggregate without passing the use case that guards its invariants — and would do so by accident,
 * because a getter returning a managed entity looks exactly like one returning data.
 *
 * <p>A record, so it is immutable without anyone having to remember to make it so.
 *
 * @param id the item's UUIDv7
 * @param name the name
 * @param description free text, may be {@code null}
 * @param kind {@code PHYSICAL} or {@code DIGITAL}, as a string so consumers need no enum of ours
 * @param itemTypeVersionId which type version the attributes below were written against
 *     (REQ-CORE-002). <b>Without it a client cannot render them</b>: what an attribute key means is
 *     the catalogue's business, and reading the schema needs its id. It was absent from this view
 *     until 2026-09-21, while the very next line told a client to go and read that schema — one of
 *     the two sentences had to be wrong, and the code was
 * @param locationId where it is; {@code null} for a digital item
 * @param quantity how many
 * @param quantityUnit the unit, may be {@code null}
 * @param attributes the fields the item's type version declares, as JSON text. The source of
 *     truth for every attribute (ADR-0004); what a key means is the catalog's business, and a
 *     client reads the type version's schema to render it
 * @param notes the paragraph a person wrote, in limited Markdown with the HTML already removed
 * @param minimumStock the level below which this consumable needs restocking, or {@code null}
 * @param lifecycleState where the item is in its life, as an {@link ItemState} name —
 *     {@code ACTIVE}, {@code LENT}, {@code ARCHIVED}, {@code TRASHED}, {@code SOLD} or
 *     {@code DISPOSED} (04 §4.4). *Said "stage 0 always ACTIVE" until 2026-09-20, by which
 *     time trashing, lending and disposal all moved it*
 * @param createdAt when it was created
 * @param updatedAt when it last changed
 * @param version the optimistic lock, which a client echoes back on update to detect a concurrent
 *     edit rather than overwriting one
 * @param maintenanceIntervalDays how often it needs servicing, in days, or {@code null} when
 *     nothing reminds about it (REQ-LIFE-004)
 */
public record ItemView(
    UUID id,
    String name,
    String description,
    String kind,
    UUID itemTypeVersionId,
    UUID locationId,
    BigDecimal quantity,
    String quantityUnit,
    String attributes,
    String notes,
    BigDecimal minimumStock,
    String lifecycleState,
    Instant createdAt,
    Instant updatedAt,
    Valuation valuation,
    long version,
    Integer maintenanceIntervalDays) {}
