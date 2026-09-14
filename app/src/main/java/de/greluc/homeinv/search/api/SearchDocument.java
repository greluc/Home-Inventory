/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.search.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One item as a search engine holds it (REQ-SRCH-011).
 *
 * <p>Assembled from four blocks' published ports: the row and its mirrored attribute values from
 * {@code inventory}, the tag names from {@code tagging}, the path from {@code locations}. Nothing
 * joins a foreign schema to build it (ADR-0002), which is why it is a record rather than a view.
 *
 * <h2>It is derived, and it is not authoritative</h2>
 *
 * <p>What an engine answers with is ids; the rows are re-loaded through the ordinary read, where
 * row-level security and the field visibility rules apply (REQ-SRCH-007, ADR-0008). So this
 * document may be stale — at most by the outbox's lag — and a stale one costs one hit too many or
 * too few, never wrong content and never somebody else's item.
 *
 * <p>It holds no {@code sensitive} value. Such a field is stored as ciphertext (ADR-0019), is never
 * mirrored into {@code item_attr_index}, and therefore never reaches {@code attributeValues}: an
 * index over ciphertext matches nothing and leaks that there is something to match.
 *
 * @param tenantId whose item. Every query filters on it, and a test checks that every query does
 *     (ADR-0008) — the index is one for all tenants, so this field is the boundary
 * @param itemId the item, which is also the document's id: indexing the same item twice replaces
 *     rather than duplicates, which is what makes an at-least-once delivery harmless
 * @param itemTypeVersionId the version it is written against, for the type and category facets
 * @param name what it is called
 * @param description the prose, or {@code null}
 * @param notes the notes, or {@code null}
 * @param attributeValues every mirrored attribute value as text, sealed ones excluded
 * @param tags the names of the tags on it
 * @param locationId where it is, or {@code null}
 * @param locationPath the ids from the root of the location tree down to {@code locationId}, so
 *     that "everything in the cellar" is one term match rather than a tree walk per hit
 * @param createdAt when it was created, the first key of the keyset order
 * @param updatedAt when it last changed
 */
public record SearchDocument(
    UUID tenantId,
    UUID itemId,
    UUID itemTypeVersionId,
    String name,
    String description,
    String notes,
    List<String> attributeValues,
    List<String> tags,
    UUID locationId,
    List<UUID> locationPath,
    Instant createdAt,
    Instant updatedAt) {}
