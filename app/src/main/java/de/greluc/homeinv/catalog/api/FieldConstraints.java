/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.catalog.api;

import java.math.BigDecimal;

/**
 * The declarative limits a field puts on its values — the whole of them (ADR-0020).
 *
 * <p>Every member is expressible in JSON Schema and evaluable with the value in hand, which is what
 * lets a client offline reach the same verdict as the server (REQ-CORE-027). There is deliberately
 * nothing here that reads another item, calls out, or evaluates an expression: those are the three
 * things ADR-0020 excludes, and the place they belong is a plugin.
 *
 * <p>A {@code null} member is no constraint at all. A record rather than a map, so that a typo in a
 * constraint name is a refused field definition rather than a limit that silently does nothing.
 *
 * @param pattern a regular expression the whole value must match, for {@code text}, {@code
 *     multiline}, {@code url}, {@code email} and {@code secret}. Anchored by the validator, so
 *     {@code [0-9]+} means the entire value and not a digit somewhere in it
 * @param min the smallest permitted number, for {@code integer}, {@code decimal}, {@code money} and
 *     {@code quantity} — compared against the amount, never against the unit
 * @param max the largest permitted number, by the same rule
 * @param minLength the fewest characters, for the text-shaped kinds
 * @param maxLength the most characters. Absent, the 64 KiB ceiling on the whole attribute set is the
 *     only limit, which is a limit on the wrong thing
 * @param unit the one unit a {@code quantity} field accepts, or the one ISO 4217 code a {@code
 *     money} field accepts. Declaring it is what makes a total over the field meaningful:
 *     REQ-CORE-032 forbids a mixed-unit sum, and a field that accepts any unit can only be summed
 *     per unit
 * @param referenceKind what a {@code reference} field may point at
 */
public record FieldConstraints(
    String pattern,
    BigDecimal min,
    BigDecimal max,
    Integer minLength,
    Integer maxLength,
    String unit,
    ReferenceKind referenceKind) {

  /** What a {@code reference} field points at (04 §4.3). */
  public enum ReferenceKind {
    /** Another item of the same tenant. */
    ITEM,
    /** A location of the same tenant. */
    LOCATION,
    /** An entry of a value list of the same tenant. */
    VALUE_LIST
  }

  /** No limits at all, which is what a field without a {@code constraints} object declares. */
  public static final FieldConstraints NONE =
      new FieldConstraints(null, null, null, null, null, null, null);

  /**
   * Whether this declares no limit whatsoever.
   *
   * @return true when every member is absent
   */
  public boolean isEmpty() {
    return pattern == null
        && min == null
        && max == null
        && minLength == null
        && maxLength == null
        && unit == null
        && referenceKind == null;
  }
}
