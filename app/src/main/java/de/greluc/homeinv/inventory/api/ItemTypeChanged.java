/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.api;

import java.util.UUID;

/**
 * An item was written against another type (REQ-CORE-011, REQ-SRCH-005).
 *
 * <p>The rarest of the six and the one with the widest effect: the attribute set is re-validated
 * against the new version and the fields it does not declare are dropped, so a consumer mirroring
 * attributes has to rebuild rather than patch.
 *
 * <p>Delivery is at least once; consumers are idempotent (04 §4.4).
 *
 * @param tenantId whose data changed
 * @param itemId the item
 * @param fromItemTypeVersionId the version it was written against before
 * @param toItemTypeVersionId the version it is written against now
 */
public record ItemTypeChanged(
    UUID tenantId, UUID itemId, UUID fromItemTypeVersionId, UUID toItemTypeVersionId) {}
