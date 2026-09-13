/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tagging.api;

import java.util.UUID;

/**
 * Two tags became one, and every assignment of the source moved (REQ-CORE-063).
 *
 * <p>Carries both ids because a consumer holding the source has to learn what it became: a saved
 * search filtering on the old tag is still a valid search, over the new one.
 *
 * @param tenantId whose tags changed
 * @param sourceId the tag that became a tombstone
 * @param targetId the tag that absorbed it
 * @param movedAssignments how many assignments changed hands; the duplicates that were dropped are
 *     not counted, because nothing moved for them
 */
public record TagMerged(UUID tenantId, UUID sourceId, UUID targetId, int movedAssignments) {}
