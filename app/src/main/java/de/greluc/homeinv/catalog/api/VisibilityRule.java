/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.catalog.api;

/**
 * When a field is shown: one condition, over one other field of the same item (REQ-CORE-028).
 *
 * <h2>Why it is this small</h2>
 *
 * <p>ADR-0020 permits "a simple condition over another field of the same item" and gives the example
 * that decides the shape: show {@code warrantyUntil} when {@code hasWarranty} is true. It permits no
 * conjunction, no nesting and no reference to a second item — because each of those is a step toward
 * an expression language that would have to evaluate identically in Java, TypeScript and Kotlin, and
 * the line against that is the core of that decision.
 *
 * <p>A rule is presentation only. A hidden field is not cleared, not exempted from {@code required}
 * and not removed from the generated schema: hiding a value is not the same as not having one, and a
 * schema that changed shape with the data would be a schema a client could not cache.
 *
 * @param field the key of the field this one watches, which must be another field of the same type
 *     version — a rule pointing at itself is a refused definition
 * @param operator how the watched value is compared
 * @param value what it is compared against, as the JSON text of a scalar ({@code true}, {@code 3},
 *     {@code "new"}). Ignored by {@link Operator#PRESENT} and {@link Operator#ABSENT}
 */
public record VisibilityRule(String field, Operator operator, String value) {

  /** The comparisons a rule may make. */
  public enum Operator {
    /** The watched field equals the value. */
    EQUALS("equals"),
    /** The watched field is present and does not equal the value. */
    NOT_EQUALS("notEquals"),
    /** The watched field carries any value at all. */
    PRESENT("present"),
    /** The watched field carries no value. */
    ABSENT("absent");

    private final String token;

    Operator(String token) {
      this.token = token;
    }

    /**
     * The spelling used in the stored rule and in the API.
     *
     * @return the token, for example {@code notEquals}
     */
    public String token() {
      return token;
    }

    /**
     * The operator a token names.
     *
     * @param token the token as stored
     * @return the operator
     * @throws IllegalArgumentException when no operator uses that token
     */
    public static Operator ofToken(String token) {
      for (Operator candidate : values()) {
        if (candidate.token.equals(token)) {
          return candidate;
        }
      }
      throw new IllegalArgumentException(
          "Unknown visibility operator '" + token + "'. Supported: equals, notEquals, present, absent.");
    }

    /**
     * Whether this operator compares against a value at all.
     *
     * @return false for {@link #PRESENT} and {@link #ABSENT}
     */
    public boolean needsValue() {
      return this == EQUALS || this == NOT_EQUALS;
    }
  }
}
