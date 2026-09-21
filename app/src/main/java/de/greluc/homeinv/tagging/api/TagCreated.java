/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tagging.api;

import de.greluc.homeinv.platform.TenantScopedEvent;
import java.util.UUID;

/**
 * A tenant created a tag (04 §4.3).
 *
 * @param tenantId whose tags changed
 * @param tagId the tag
 * @param name the name it was given
 */
public record TagCreated(UUID tenantId, UUID tagId, String name) implements TenantScopedEvent {}
