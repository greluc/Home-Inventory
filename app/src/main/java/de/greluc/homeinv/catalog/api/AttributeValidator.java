/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.catalog.api;

import java.util.List;
import java.util.UUID;

/**
 * The binding check that an attribute set belongs to its type version (REQ-CORE-005).
 *
 * <h2>The second of three places</h2>
 *
 * <p>07 §7.3 makes validation deliberately redundant: the client checks against the shipped schema
 * for instant feedback and to work offline, the database checks the basic shape so a write that
 * bypasses the application cannot store nonsense, and this checks the same document the client got.
 * The redundancy is the design, not duplication to be removed — the first can be skipped by anyone
 * writing their own client, and the third cannot see a field definition.
 *
 * <p>Against the generated document rather than against the field definitions (ADR-0056), so that
 * "a client validates offline with the same result as the server" is one implementation checked
 * twice rather than two implementations hoped to agree.
 */
public interface AttributeValidator {

  /**
   * Checks an attribute set against a type version.
   *
   * <p>Covers what the schema can state: types, required fields, patterns, ranges, enumerations, and
   * the refusal of a key the version does not declare. It does not cover what a schema cannot know —
   * that a {@code reference} points at a row of this tenant, or that a {@code secret} may be written
   * by this caller. Those are the caller's, and 07 §7.3 names them.
   *
   * @param typeVersionId an item type version or a location category version
   * @param attributesJson the attribute set as JSON text, an object
   * @return the violations, empty when the set is valid
   * @throws TypeRegistry.UnknownTypeException when the version is not visible to this tenant
   */
  ValidationResult validate(UUID typeVersionId, String attributesJson);

  /**
   * What a check found.
   *
   * @param violations every violation, in the order the validator reported them; empty means valid
   */
  record ValidationResult(List<Violation> violations) {

    /** A set with nothing wrong with it. */
    public static final ValidationResult VALID = new ValidationResult(List.of());

    /**
     * Whether the attribute set may be written.
     *
     * @return true when nothing was found
     */
    public boolean valid() {
      return violations.isEmpty();
    }
  }

  /**
   * One thing wrong with one value.
   *
   * @param path the JSON pointer of the offending value relative to the attribute set, for example
   *     {@code /purchasePrice/currency}. This is what {@code 422} carries, because a client that is
   *     told only "invalid" has to guess which field to highlight (REQ-CORE-005)
   * @param message what is wrong, in English and without the value — a violation travels into logs,
   *     and a log line that quotes a rejected {@code secret} is a leak
   */
  record Violation(String path, String message) {}
}
