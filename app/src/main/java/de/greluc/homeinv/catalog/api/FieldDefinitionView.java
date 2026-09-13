/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.catalog.api;

import java.util.Map;
import java.util.UUID;

/**
 * One field of one type version, as everything outside {@code catalog} sees it.
 *
 * <p>A view and not the entity (REQ-NFR-022): {@code inventory} needs to know that {@code
 * purchasePrice} is money and is searchable; it has no business holding a row it could write back.
 *
 * @param id the definition, stable across the life of the version it belongs to
 * @param key the attribute key — {@code isbn}, {@code purchasePrice} — which is what appears in
 *     {@code item.attributes} and in {@code item_attr_index.field_key}
 * @param dataType what kind of value it holds
 * @param labels the field's name per language tag, as the tenant wrote it. Multilingual data rather
 *     than interface text: the server cannot translate a word a tenant invented (REQ-NFR-032)
 * @param helpTexts the explanatory line per language tag, empty when the tenant wrote none
 * @param required whether a value must be present. A deprecated field is never required, whatever
 *     this says — see {@link #effectivelyRequired()}
 * @param defaultValue the JSON text of the value a new item starts with, or {@code null}
 * @param constraints the declarative limits, never {@code null} — {@link FieldConstraints#NONE} when
 *     the definition carries none
 * @param valueListId the list an {@code enum} or {@code multi-enum} draws from, {@code null}
 *     otherwise
 * @param visibility the one condition that decides whether a client shows this field, or {@code
 *     null} when it is always shown
 * @param group the form section the tenant put it in, or {@code null}
 * @param displayOrder the position within that group; ties are broken by key, so the order is total
 * @param searchable whether it is mirrored into the side table for filtering
 * @param sortable whether it is mirrored for ordering
 * @param facetable whether it is mirrored for counting
 * @param sensitive whether reading it needs a permission of its own and it is encrypted at rest
 *     (ADR-0019)
 * @param deprecated whether it is hidden from new input while its values remain (REQ-CORE-026)
 */
public record FieldDefinitionView(
    UUID id,
    String key,
    FieldDataType dataType,
    Map<String, String> labels,
    Map<String, String> helpTexts,
    boolean required,
    String defaultValue,
    FieldConstraints constraints,
    UUID valueListId,
    VisibilityRule visibility,
    String group,
    int displayOrder,
    boolean searchable,
    boolean sortable,
    boolean facetable,
    boolean sensitive,
    boolean deprecated) {

  /**
   * Whether an item must carry a value for this field.
   *
   * <p>A deprecated field is never required, whatever its own flag says. REQ-CORE-026 keeps the
   * values of a deprecated field and hides it from input; a hidden field that still had to be filled
   * in would make every new item unsaveable, and the tenant who deprecated it would have no way to
   * see why.
   *
   * @return true when a value is demanded of a new item
   */
  public boolean effectivelyRequired() {
    return required && !deprecated;
  }

  /**
   * Whether this field is mirrored into {@code inventory.item_attr_index} at all.
   *
   * <p>Three flags decide it and two things have a veto. A {@code secret} data type is never
   * projected, because the side table answers filters and a value gated by a permission must not be
   * filterable by somebody without it. Neither is anything marked {@code sensitive}: it is stored
   * sealed (ADR-0019), so the only thing that could be mirrored is ciphertext, and a filter over
   * ciphertext matches nothing while looking as though it works.
   *
   * <p>The type editor refuses the combination outright, so this is belt and braces — and it is the
   * half that also covers a field that was marked sensitive after it was already searchable.
   *
   * @return true when a write must project this field
   */
  public boolean projected() {
    return (searchable || sortable || facetable) && dataType.projectable() && !sensitive;
  }
}
