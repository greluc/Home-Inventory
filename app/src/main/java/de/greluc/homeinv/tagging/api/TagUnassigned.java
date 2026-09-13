/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tagging.api;

import java.util.UUID;

/**
 * A tag was taken off an item or a place.
 *
 * <p>Also raised for each tag an exclusive group displaces: assigning "used" where "new" was is one
 * assignment and one unassignment, and a consumer that saw only the first would believe a thing
 * carried both.
 *
 * @param tenantId whose data changed
 * @param tagId the tag
 * @param target what kind of thing it came off
 * @param targetId the item or place
 */
public record TagUnassigned(
    UUID tenantId, UUID tagId, TagService.TagTarget target, UUID targetId) {}
