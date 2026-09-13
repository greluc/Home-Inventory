/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.catalog.api;

import java.util.UUID;

/**
 * A field is no longer offered for input, and every value under it stays (REQ-CORE-026).
 *
 * <p>Not a deletion, and the distinction is the point: a consumer that treated this as one would
 * drop data ADR-0020 keeps deliberately.
 *
 * @param tenantId whose type system changed
 * @param versionId the version the field belongs to
 * @param fieldId the definition
 * @param key the attribute key whose values remain
 */
public record FieldDeprecated(UUID tenantId, UUID versionId, UUID fieldId, String key) {}
