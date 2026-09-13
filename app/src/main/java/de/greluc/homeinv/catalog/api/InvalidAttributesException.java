/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.catalog.api;

import java.util.List;

/**
 * An attribute set does not match the type version it was written against (REQ-CORE-005).
 *
 * <h2>Why the validator does not throw this itself</h2>
 *
 * <p>{@link AttributeValidator} returns a result: a caller may want to know what is wrong without
 * the answer being an exception — an import that reports every bad row, a preview that shows a form
 * in red. The blocks that write attributes turn a failed result into this, because for them there is
 * nothing to do with one but refuse the write.
 *
 * <p>Every violation carries its JSON pointer, and the REST layer passes them through as
 * {@code errors}: REQ-CORE-005's acceptance is "{@code 422} with the field path", and a client told
 * only that something is invalid has to guess which field to mark.
 */
public class InvalidAttributesException extends RuntimeException {

  private final transient List<AttributeValidator.Violation> violations;

  /**
   * Carries what the validator found.
   *
   * @param violations the violations, never empty — an empty list would mean the write should have
   *     gone ahead
   */
  public InvalidAttributesException(List<AttributeValidator.Violation> violations) {
    super("The attributes do not match the type: " + violations.size() + " violation(s).");
    this.violations = List.copyOf(violations);
  }

  /**
   * What was wrong, in the order the validator reported it.
   *
   * @return the violations
   */
  public List<AttributeValidator.Violation> getViolations() {
    return violations;
  }
}
