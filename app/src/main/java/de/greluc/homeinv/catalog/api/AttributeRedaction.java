/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.catalog.api;

import java.util.UUID;

/**
 * Removes the sensitive attributes a caller may not read (REQ-TEN-008, REQ-SEC-027).
 *
 * <p><b>Removed, not masked.</b> 12 §12.5 is explicit about the difference, and it is not
 * cosmetic: a masked field says that the field exists and how long its value is, and for a purchase
 * price that is most of what somebody was after.
 *
 * <p>Here rather than in {@code inventory} or {@code locations}, because which fields are sensitive
 * is a fact about the <em>type system</em>, and both of those blocks already ask this one what
 * their attributes mean. Whether the caller may read one is
 * {@code authorization}'s answer, and this block asks for it.
 */
public interface AttributeRedaction {

  /**
   * The attributes as this caller may see them.
   *
   * <p>Null and blank pass through unchanged: an item with no attributes has nothing to redact, and
   * inventing an empty object here would change what a client receives for reasons that have
   * nothing to do with visibility.
   *
   * <p>A sensitive value the caller <em>may</em> read is <b>opened</b> here, because it is stored
   * sealed (ADR-0019). That is why this takes the entity: the seal is bound to the tenant, the
   * record and the field, so opening one needs to know which record it belongs to. Passing the
   * wrong id does not produce the wrong value — it produces no value, which is the point of
   * binding it.
   *
   * @param typeVersionId the version the attributes were written against, which says which keys are
   *     sensitive
   * @param entityId the item or location the attributes belong to
   * @param attributesJson the stored attributes as JSON text
   * @return the same JSON with the sensitive keys the caller may not read removed, and the ones it
   *     may read opened
   */
  String forCaller(UUID typeVersionId, UUID entityId, String attributesJson);
}
