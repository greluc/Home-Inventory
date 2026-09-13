/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.catalog.api;

import java.util.UUID;

/**
 * A draft became the version new items and locations are written against.
 *
 * <p>The moment a type's shape becomes binding: the schema is frozen, the inherited fields are
 * materialised, and anything created from now on names this version (REQ-CORE-025).
 *
 * @param tenantId whose type system changed
 * @param ownerId the item type or the location category
 * @param versionId the version that was published
 * @param versionNumber its position in the series, which is what a person reads
 */
public record TypeVersionPublished(
    UUID tenantId, UUID ownerId, UUID versionId, int versionNumber) {}
