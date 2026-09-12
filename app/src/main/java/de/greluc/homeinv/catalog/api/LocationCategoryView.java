/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.catalog.api;

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
 * <p>That changes at stage 1, when a tenant may define its own: a category nobody shipped has no
 * key in any bundle, and its labels then travel with the row as multilingual data (REQ-CORE-031).
 *
 * @param id the category, which is what a location references
 * @param key the shipped key — {@code room}, {@code shelf}, {@code box} — which a client translates
 * @param mobile whether locations of this category travel with their contents; every shipped one is
 *     stationary at stage 0, and the field exists because the column does
 */
public record LocationCategoryView(UUID id, String key, boolean mobile) {}
