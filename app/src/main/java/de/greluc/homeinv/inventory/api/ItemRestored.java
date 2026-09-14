/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.api;

import java.util.UUID;

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
public record ItemRestored(UUID tenantId, UUID itemId) {}
