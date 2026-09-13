/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.catalog.api;

import java.util.UUID;

/**
 * A draft version gained a field.
 *
 * <p>Carried while the version is still a draft, so a consumer learns of the field before anything
 * can hold a value for it.
 *
 * @param tenantId whose type system changed
 * @param versionId the draft the field was added to
 * @param fieldId the definition
 * @param key the attribute key items will carry
 */
public record FieldAdded(UUID tenantId, UUID versionId, UUID fieldId, String key) {}
