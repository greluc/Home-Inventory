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
 * @param locationId where it is; {@code null} for a digital item
 * @param quantity how many
 * @param quantityUnit the unit, may be {@code null}
 * @param lifecycleState where the item is in its life; stage 0 always {@code ACTIVE}
 * @param createdAt when it was created
 * @param updatedAt when it last changed
 * @param version the optimistic lock, which a client echoes back on update to detect a concurrent
 *     edit rather than overwriting one
 */
public record ItemView(
    UUID id,
    String name,
    String description,
    String kind,
    UUID locationId,
    BigDecimal quantity,
    String quantityUnit,
    String lifecycleState,
    Instant createdAt,
    Instant updatedAt,
    long version) {}
