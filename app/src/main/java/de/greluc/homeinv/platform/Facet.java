/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.platform;

import java.util.List;
import java.util.Objects;

/**
 * How many rows a list would still hold for each value of one dimension (REQ-SRCH-002).
 *
 * <p>The sidebar beside a list: "Werkzeug 41, Buch 12", "defekt 4", "Schuppen 18". A client renders
 * the buckets and puts a bucket's {@link Bucket#value()} straight back into a {@code filter}
 * parameter — which is why a bucket carries the token the filter grammar takes and not a display
 * name. A type is its key, a tag its name, a location its id, an attribute its stored value; the
 * names come from the endpoints that own them, because a count is not the place to learn what
 * something is called in a language.
 *
 * <h2>A facet is counted without its own filter</h2>
 *
 * <p>Decided with the owner on 2026-09-14. With {@code filter=tag:broken} active, the tag facet
 * still counts every tag — "neu 12, gebraucht 30, defekt 4" — because the whole point of the
 * sidebar is to show where one could click next. Counted <i>with</i> its own filter it would show
 * one tag and the total, which is a number nobody needs and a control nobody can use. Every
 * <i>other</i> dimension's filters do narrow it, so the counts still describe the list somebody is
 * looking at.
 *
 * <p>That makes "facet counts agree with the results" true of a dimension nobody filtered by and
 * deliberately untrue of one somebody did. REQ-SRCH-002's acceptance says so since that decision.
 *
 * @param dimension what was counted, spelled as the {@code facet} and {@code filter} parameters
 *     spell it: {@code type}, {@code category}, {@code tag}, {@code location} or
 *     {@code attr.<key>}
 * @param buckets the values and their counts, most first
 */
public record Facet(String dimension, List<Bucket> buckets) {

  /**
   * How many buckets one dimension answers with.
   *
   * <p>A tenant with four hundred tags would otherwise put four hundred numbers into every response
   * for a sidebar that shows a dozen. The buckets are the largest ones, so what is cut off is what
   * nobody was going to click.
   */
  public static final int MAX_BUCKETS = 50;

  /**
   * Copies the buckets, so a counted facet cannot be changed behind a caller's back.
   *
   * @throws NullPointerException when either part is missing
   */
  public Facet {
    Objects.requireNonNull(dimension, "A facet says what it counted");
    buckets = List.copyOf(Objects.requireNonNull(buckets, "A facet has buckets, possibly none"));
  }

  /**
   * One value of the dimension, and how many rows carry it.
   *
   * @param value the token a {@code filter} takes for this bucket — a type key, a tag name, a
   *     location id, an attribute value
   * @param count how many rows
   */
  public record Bucket(String value, long count) {}
}
