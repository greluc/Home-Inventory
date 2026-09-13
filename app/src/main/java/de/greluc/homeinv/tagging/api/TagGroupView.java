/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tagging.api;

import java.util.Map;
import java.util.UUID;

/**
 * A group of tags — "condition", "room", "owner" (REQ-CORE-061).
 *
 * @param id the group
 * @param key the stable key, which is what an import or an export names
 * @param labels the group's name per language tag, as the tenant wrote it. Multilingual data rather
 *     than interface text: the server cannot translate a word a tenant invented (REQ-NFR-032)
 * @param exclusive whether a thing may carry only one tag of this group. "Condition" is one of new,
 *     used or broken; a thing that was both would be a thing whose condition nobody can read
 * @param displayOrder where the group sits among the others on a form
 */
public record TagGroupView(
    UUID id, String key, Map<String, String> labels, boolean exclusive, int displayOrder) {}
