/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.api;

import java.util.UUID;

/**
 * An item was put somewhere else (REQ-SRCH-005, REQ-CORE-049).
 *
 * <p>Its own event and not an {@link ItemUpdated}, because "what is in the shed" is a question a
 * consumer answers differently from "what is this called": a move changes the location path in a
 * search document and nothing else, while a rename changes the text and not the path.
 *
 * <p>Both ends are carried. Where something came from is not recoverable afterwards, and a consumer
 * that maintains a count per place needs to decrement the old one.
 *
 * <p>Delivery is at least once; consumers are idempotent (04 §4.4).
 *
 * @param tenantId whose data changed
 * @param itemId the item
 * @param fromLocationId where it was, or {@code null} when it was nowhere
 * @param toLocationId where it is now, or {@code null} when it is now nowhere
 */
public record ItemMoved(UUID tenantId, UUID itemId, UUID fromLocationId, UUID toLocationId) {}
