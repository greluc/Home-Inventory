/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tagging.api;

import java.util.UUID;

/**
 * A tag was put on an item or a place.
 *
 * @param tenantId whose data changed
 * @param tagId the tag
 * @param target what kind of thing it went on
 * @param targetId the item or place
 */
public record TagAssigned(
    UUID tenantId, UUID tagId, TagService.TagTarget target, UUID targetId) {}
