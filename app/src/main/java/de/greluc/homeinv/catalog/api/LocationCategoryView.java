/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.catalog.api;

import jakarta.annotation.Nullable;
import java.util.Map;
import java.util.UUID;

/**
 * A kind of place a location can be: a room, a shelf, a box.
 *
 * <h2>Why the key and not a label</h2>
 *
 * <p>The categories of stage 0 are shipped, not configured — twelve fixed keys, the same for every
 * tenant. Their names are <em>interface text</em>, so they belong in the client's resource bundle
 * with everything else a user reads, and REQ-NFR-032 forbids display text anywhere else. A label
 * returned from here would be one the server would have to translate, in a language the server does
 * not know the user prefers.
 *
 * <p>That changed at stage 1, and the labels are here now. A category nobody shipped has no key in
 * any bundle, so it has to carry its own name; a shipped one may have been renamed by its tenant
 * (REQ-CORE-042, "all present and editable"), and that name has to win over the translation.
 *
 * <p>So the rule for a client is one sentence: <b>use the label for the reader's language when
 * there is one, and translate the key when there is not.</b> Nothing here is display text the
 * server invented — every label in this map was typed by somebody in the tenant, which is what
 * keeps REQ-NFR-032 true.
 *
 * @param id the category, which is what a location references
 * @param key the shipped key — {@code room}, {@code shelf}, {@code box} — which a client translates
 *     when the tenant has given the category no name of its own
 * @param labels the tenant's own name per language tag, empty when it has none
 * @param icon an icon name for the client, or {@code null}
 * @param mobile whether locations of this category travel with their contents. Every shipped one is
 *     stationary; the flag marks the relocation case a client offers, not a restriction on moving
 */
public record LocationCategoryView(
    UUID id, String key, Map<String, String> labels, @Nullable String icon, boolean mobile) {}
