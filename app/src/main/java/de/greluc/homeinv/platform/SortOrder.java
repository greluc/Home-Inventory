/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.platform;

/**
 * One ordering, as a caller asked for it (REQ-SRCH-004).
 *
 * <h2>Why it is in the shared kernel</h2>
 *
 * <p>Two blocks need it and neither may depend on the other. {@code search} owns the question and
 * {@code inventory} owns the rows, so an ordering declared in either one puts the other's name in
 * its signature — and `search` already depends on `inventory`, which makes that a cycle. Spring
 * Modulith caught exactly that when this type first lived in {@code search.api}.
 *
 * <p>{@link CursorCodec.Position} is here for the same reason and was the precedent: where a page
 * starts is a property of a query rather than of any one block, and so is the order it is in.
 *
 * <h2>One key</h2>
 *
 * <p>08 §8.2 once showed {@code sort=-updatedAt,name}. {@code REQ-SRCH-004} asks for "sorting over
 * sortable fields, ascending and descending", and one key is what answers it (decided with the
 * owner, 2026-09-14). Several are refused rather than reduced to the first: a caller who asked for
 * two orderings and silently received one holds a list that is wrong in a way nothing tells them
 * about.
 *
 * <p>Underneath whatever is chosen sits the tie-break the keyset cursor pages by. A name is no more
 * unique than a timestamp, and a page boundary on a non-unique key is how keyset pagination repeats
 * or loses a row.
 *
 * @param field what to order by: a built-in column, or {@code attr.<key>} for an attribute the
 *     tenant marked sortable. Checked against that allowlist before it reaches any statement —
 *     never interpolated from what a caller typed
 * @param descending whether to reverse it; the wire spells this as a leading minus
 */
public record SortOrder(String field, boolean descending) {

  /**
   * How an attribute is named on the wire, to tell it from a column of the thing itself.
   *
   * <p>Shared with {@link QueryFilter}: {@code sort=attr.manufacturer} and
   * {@code filter=attr.manufacturer:Stanley} mean the same {@code attr.} and would drift apart
   * as two constants.
   */
  public static final String ATTRIBUTE_PREFIX = "attr.";

  /**
   * Whether this orders by an attribute rather than by a column of the row.
   *
   * @return true when the field names an attribute
   */
  public boolean isAttribute() {
    return field.startsWith(ATTRIBUTE_PREFIX);
  }

  /**
   * The attribute key, without the prefix.
   *
   * @return the key as {@code item_attr_index.field_key} holds it
   * @throws IllegalStateException when this is not an attribute ordering
   */
  public String attributeKey() {
    if (!isAttribute()) {
      throw new IllegalStateException(field + " is a column, not an attribute");
    }
    return field.substring(ATTRIBUTE_PREFIX.length());
  }
}
