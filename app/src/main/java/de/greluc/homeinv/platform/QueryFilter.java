/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.platform;

import java.util.List;
import java.util.Objects;

/**
 * One condition a query puts on an attribute (REQ-SRCH-003).
 *
 * <p>The wire spells it {@code filter=attr.<key>:<op>:<value>}, repeatable, and every filter given
 * has to hold — they are joined by "and", because a person narrowing a list expects each click to
 * narrow it further.
 *
 * <h2>Why it is in the shared kernel</h2>
 *
 * <p>{@code search} owns the question and {@code inventory} owns the rows, and neither may depend on
 * the other: {@code search} already depends on {@code inventory}, so a filter type declared in
 * {@code search} would put its name in {@code inventory}'s signature and close a cycle. That is
 * exactly what happened to {@link SortOrder} before it moved here, and {@link CursorCodec.Position}
 * has been here all along for the same reason.
 *
 * <h2>A dimensioned value is compared within its dimension</h2>
 *
 * <p>{@code item_attr_index} keeps {@code unit_value} beside {@code num_value} precisely so that a
 * total never adds euros to dollars. A comparison is a subtraction with the sign thrown away, so it
 * inherits the same rule: a filter on a {@code money} or {@code quantity} field <b>must</b> name its
 * unit and is refused without one (decided with the owner, 2026-09-14). 08 §8.2 showed
 * {@code attr.purchasePrice:gte:100} without one, and that example was wrong.
 *
 * @param field the attribute key, without the {@code attr.} prefix the wire uses
 * @param operator how to compare
 * @param values what to compare against — one value, or several for {@link Operator#IN}
 * @param unit the currency or unit the comparison happens in, or {@code null} for a field that
 *     carries no dimension
 */
public record QueryFilter(String field, Operator operator, List<String> values, String unit) {

  /**
   * Copies the values and refuses a filter that cannot mean anything.
   *
   * @throws NullPointerException when the field, operator or values are missing
   * @throws IllegalArgumentException when no value is given, or several are given to an operator
   *     that compares against one
   */
  public QueryFilter {
    Objects.requireNonNull(field, "A filter names a field");
    Objects.requireNonNull(operator, "A filter names how to compare");
    values = List.copyOf(Objects.requireNonNull(values, "A filter names what to compare against"));
    if (values.isEmpty()) {
      throw new IllegalArgumentException("A filter on " + field + " names no value");
    }
    if (values.size() > 1 && operator != Operator.IN) {
      throw new IllegalArgumentException(
          operator.token() + " compares against one value, not " + values.size());
    }
  }

  /**
   * Whether this asks for an ordering between values rather than for equality.
   *
   * <p>What decides whether a unit is required: "is it 100" is answerable across currencies — no
   * amount of euros equals a dollar amount either — while "is it over 100" silently invites the
   * comparison that ADR-0025 keeps out of the core.
   *
   * @return true for the four range operators
   */
  public boolean isRange() {
    return operator == Operator.GT
        || operator == Operator.GTE
        || operator == Operator.LT
        || operator == Operator.LTE;
  }

  /**
   * How two values are compared.
   *
   * <p>A closed set, and deliberately small. Each token is what the wire spells and what a caller
   * types; anything else is refused rather than guessed at.
   */
  public enum Operator {
    /** Equal to the value. The operator a filter has when none is given. */
    EQ("eq"),
    /** One of several values. */
    IN("in"),
    /** Greater than. */
    GT("gt"),
    /** Greater than or equal to. */
    GTE("gte"),
    /** Less than. */
    LT("lt"),
    /** Less than or equal to. */
    LTE("lte");

    private final String token;

    Operator(String token) {
      this.token = token;
    }

    /**
     * The token the wire uses.
     *
     * @return the lower-case token
     */
    public String token() {
      return token;
    }

    /**
     * Whether a token names an operator at all.
     *
     * <p>Asked before {@link #ofToken(String)} where the answer decides how to read the rest of the
     * parameter, so that a filter value which happens to spell {@code lt} is still a value.
     *
     * @param token as it arrived
     * @return true when some operator uses it
     */
    public static boolean isToken(String token) {
      for (Operator operator : values()) {
        if (operator.token.equals(token)) {
          return true;
        }
      }
      return false;
    }

    /**
     * The operator a token names.
     *
     * @param token as it arrived
     * @return the operator
     * @throws IllegalArgumentException when no operator has that token
     */
    public static Operator ofToken(String token) {
      for (Operator operator : values()) {
        if (operator.token.equals(token)) {
          return operator;
        }
      }
      throw new IllegalArgumentException("No such filter operator: " + token);
    }
  }
}
