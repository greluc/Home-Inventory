/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.locations.api;

import java.util.List;
import java.util.UUID;

/**
 * What other blocks and the REST layer see of a location.
 *
 * <p>{@code ancestors} is the readable form of the materialised path: the names from the root down
 * to this location, assembled from the rows the path names. The path itself is made of ids and is
 * deliberately not published - it is an index structure, and publishing it would invite a client to
 * parse it.
 *
 * @param id the location
 * @param name the name
 * @param categoryId the category
 * @param parentId the parent, or {@code null} for a root
 * @param depth distance from the root
 * @param ancestors the names from the root down to and including this location (REQ-CORE-044)
 */
public record LocationView(
    UUID id, String name, UUID categoryId, UUID parentId, int depth, List<String> ancestors) {}
