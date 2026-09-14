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
 * <p>The wire spells it {@code filter=<dimension>:<op>:<value>}, repeatable, and every filter
 * given has to hold — they are joined by "and", because a person narrowing a list expects each
 * click to narrow it further. Within one filter {@link Operator#IN} is an "or", which is what makes
 * a multi-select facet work: two ticked tags widen, two separate filters narrow.
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
 * @param dimension what is being narrowed
 * @param field the attribute key, without the {@code attr.} prefix the wire uses — {@code null}
 *     for every dimension but {@link Dimension#ATTRIBUTE}, which is the only one with more than one
 *     field to name
 * @param operator how to compare
 * @param values what to compare against — one value, or several for {@link Operator#IN}
 * @param unit the currency or unit the comparison happens in, or {@code null} for a field that
 *     carries no dimension
 */
public record QueryFilter(
    Dimension dimension, String field, Operator operator, List<String> values, String unit) {

  /**
   * Copies the values and refuses a filter that cannot mean anything.
   *
   * @throws NullPointerException when the field, operator or values are missing
   * @throws IllegalArgumentException when no value is given, or several are given to an operator
   *     that compares against one
   */
  public QueryFilter {
    Objects.requireNonNull(dimension, "A filter names what it narrows");
    Objects.requireNonNull(operator, "A filter names how to compare");
    values = List.copyOf(Objects.requireNonNull(values, "A filter names what to compare against"));
    if (values.isEmpty()) {
      throw new IllegalArgumentException("A filter on " + dimension.token() + " names no value");
    }
    if (values.size() > 1 && operator != Operator.IN) {
      throw new IllegalArgumentException(
          operator.token() + " compares against one value, not " + values.size());
    }
    if (dimension == Dimension.ATTRIBUTE && field == null) {
      throw new IllegalArgumentException("An attribute filter names which attribute");
    }
    if (dimension != Dimension.ATTRIBUTE && field != null) {
      throw new IllegalArgumentException(
          dimension.token() + " is one dimension and takes no field name");
    }
    if (dimension != Dimension.ATTRIBUTE && unit != null) {
      throw new IllegalArgumentException(dimension.token() + " carries no unit");
    }
    if (operator == Operator.SUBTREE && dimension != Dimension.LOCATION) {
      // The one operator that is not a comparison but a walk, and only the
      // location tree has anything to walk.
      throw new IllegalArgumentException("Only a location filter can ask for a subtree");
    }
    if (dimension != Dimension.ATTRIBUTE
        && operator != Operator.EQ
        && operator != Operator.IN
        && operator != Operator.SUBTREE) {
      throw new IllegalArgumentException(
          dimension.token() + " is a set and not an ordering; " + operator.token() + " is not one");
    }
  }

  /**
   * A convenience for the common case, an attribute compared without a unit.
   *
   * @param field the attribute key
   * @param operator how to compare
   * @param values what to compare against
   * @return the condition
   */
  public static QueryFilter onAttribute(String field, Operator operator, List<String> values) {
    return new QueryFilter(Dimension.ATTRIBUTE, field, operator, values, null);
  }

  /**
   * A filter on one of the dimensions that is not an attribute.
   *
   * @param dimension what to narrow
   * @param operator how to compare
   * @param values what to compare against
   * @return the condition
   */
  public static QueryFilter onScope(
      Dimension dimension, Operator operator, List<String> values) {
    return new QueryFilter(dimension, null, operator, values, null);
  }

  /**
   * What a filter narrows.
   *
   * <p>Four of them, and three belong to other building blocks: a type is {@code catalog}'s, a tag
   * is {@code tagging}'s and a location is {@code locations}'. {@code search} resolves each through
   * that block's published port and hands {@code inventory} nothing but ids — which is the whole
   * reason this type names the dimension rather than a column (ADR-0002).
   */
  public enum Dimension {
    /** A field of the item's type, mirrored into {@code item_attr_index}. */
    ATTRIBUTE("attr"),
    /** The item's type, named by its key. */
    TYPE("type"),
    /** A tag assigned to the item, named as a person writes it. */
    TAG("tag"),
    /** Where the item is, named by id. */
    LOCATION("location");

    private final String token;

    Dimension(String token) {
      this.token = token;
    }

    /**
     * The token the wire uses, which is the part before the first colon.
     *
     * @return the lower-case token
     */
    public String token() {
      return token;
    }

    /**
     * The dimension a token names.
     *
     * @param token as it arrived
     * @return the dimension, or {@code null} when no dimension has that token
     */
    public static Dimension ofToken(String token) {
      for (Dimension dimension : values()) {
        if (dimension.token.equals(token)) {
          return dimension;
        }
      }
      return null;
    }
  }

  /**
   * Reads one {@code filter} parameter as the wire spells it (08 §8.2).
   *
   * <p>{@code <dimension>:<op>:<value>}, where the dimension is {@code attr.<key>}, {@code type},
   * {@code tag} or {@code location}. The operator may be left out for equality, which is what makes
   * {@code type:power-tool} read the way it does. Told apart by whether the second part <i>is</i> an
   * operator rather than by counting the parts, so that a tag actually called {@code lt} cannot
   * change what its own filter means.
   *
   * <p>An attribute value is one part and anything after it is the unit. Every other dimension
   * takes the rest of the parameter as its value, colons included: a tag is named as a person wrote
   * it, and a person may well have written {@code Kitchen: top shelf}. A comma still separates the
   * values of an {@code in}, so a tag whose name contains one cannot be filtered by name — the
   * grammar spends the comma there, and that is the price.
   *
   * <p>Here and not in the REST adapter, although that is where a parameter arrives: a saved search
   * stores its filters in this same grammar and replays them (REQ-SRCH-008), so two callers read
   * it. One grammar in one place; the adapter keeps only the part that is about HTTP, which is that
   * the parameter repeats.
   *
   * <p>Translation and not a decision. Whether a field may be filtered at all, and whether this one
   * needed a unit, is the application layer's to answer against the tenant's own allowlist
   * (ADR-0010).
   *
   * @param filter one parameter
   * @return the condition it spells
   * @throws IllegalArgumentException when it names no dimension, or carries no value
   */
  public static QueryFilter parse(String filter) {
    if (filter == null || filter.isBlank()) {
      throw new IllegalArgumentException("An empty filter narrows nothing");
    }
    String[] parts = filter.split(":");

    Dimension dimension;
    String field = null;
    if (parts[0].startsWith(SortOrder.ATTRIBUTE_PREFIX)) {
      dimension = Dimension.ATTRIBUTE;
      field = parts[0].substring(SortOrder.ATTRIBUTE_PREFIX.length());
      if (field.isBlank()) {
        throw new IllegalArgumentException("A filter needs a field and a value: " + filter);
      }
    } else {
      dimension = Dimension.ofToken(parts[0]);
      if (dimension == null || dimension == Dimension.ATTRIBUTE) {
        throw new IllegalArgumentException(
            "Not a filter dimension: " + parts[0] + "; expected attr.<key>, type, tag or location");
      }
    }

    boolean named = parts.length >= 3 && Operator.isToken(parts[1]);
    Operator operator = named ? Operator.ofToken(parts[1]) : Operator.EQ;
    int valueAt = named ? 2 : 1;
    if (parts.length <= valueAt) {
      throw new IllegalArgumentException("A filter needs a value: " + filter);
    }

    String value;
    String unit = null;
    if (dimension == Dimension.ATTRIBUTE) {
      value = parts[valueAt];
      unit = parts.length > valueAt + 1 ? parts[valueAt + 1] : null;
    } else {
      value = String.join(":", java.util.Arrays.copyOfRange(parts, valueAt, parts.length));
    }
    if (value.isBlank()) {
      throw new IllegalArgumentException("A filter needs a value: " + filter);
    }

    List<String> values = operator == Operator.IN ? List.of(value.split(",")) : List.of(value);
    return new QueryFilter(dimension, field, operator, values, unit);
  }

  /**
   * Reads several {@code filter} parameters, refusing too many and one that is too long.
   *
   * @param filters the parameters as they arrived, possibly {@code null}
   * @param maxFilters how many are allowed
   * @param maxLength how long one may be
   * @return one condition per parameter, all of which must hold; empty when none was given
   * @throws IllegalArgumentException when there are too many, one is too long, or one is not of
   *     the shape {@link #parse} takes
   */
  public static List<QueryFilter> parseAll(
      java.util.Collection<String> filters, int maxFilters, int maxLength) {
    if (filters == null || filters.isEmpty()) {
      return List.of();
    }
    if (filters.size() > maxFilters) {
      throw new IllegalArgumentException(
          "At most " + maxFilters + " filters, not " + filters.size());
    }
    for (String filter : filters) {
      if (filter != null && filter.length() > maxLength) {
        throw new IllegalArgumentException("A filter is at most " + maxLength + " characters");
      }
    }
    return filters.stream().map(QueryFilter::parse).toList();
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
    LTE("lte"),
    /**
     * At this node of the location tree or anywhere beneath it.
     *
     * <p>Not a comparison: {@code locations} resolves it to the set of ids in that subtree, and
     * what reaches a statement is that set. Only {@link Dimension#LOCATION} accepts it.
     */
    SUBTREE("subtree");

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
