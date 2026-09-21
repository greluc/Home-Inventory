/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.platform;

import jakarta.annotation.Nullable;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Objects;

/**
 * One page of anything, in the shape 08 §8.2 specifies for every collection.
 *
 * <p>{@code {"data": […], "page": {…}, "meta": {…}}}. Twenty listings each had a record of their
 * own carrying {@code items} and {@code nextCursor}, which was the same shape written twenty times
 * and none of them could say that a derived store had gone down.
 *
 * <h2>Why one type rather than twenty</h2>
 *
 * <p>The envelope is a property of the API and not of any one block, so it belongs where every block
 * can reach it. The alternative — adding {@code meta} to the one listing that can degrade — was
 * considered and rejected with the owner on 2026-09-14: the document has described one shape since
 * the first commit, and two shapes in one API is the drift that document exists to prevent.
 *
 * <h2>The parts that are filled in later</h2>
 *
 * <p>A block knows its rows and its cursor. It does not know how long the request took, and only the
 * search path knows whether it answered from the fallback. Both are therefore optional and are set
 * by whoever does know — each through a method returning a new page, because this one is a record
 * and stays one.
 *
 * <h2>Facets are a fourth member, and usually absent</h2>
 *
 * <p>Counts are neither rows, nor where the next rows are, nor how the answer was produced, so they
 * are none of the three members that exist — {@code meta} says how a response came about and would
 * stop meaning that if it also carried content. A fourth member is additive, and it stays
 * {@code null} on every listing that was not asked for counts. Decided with the owner on
 * 2026-09-14.
 *
 * <p>{@code facets} is the <b>one</b> member this API omits when it is null, and it is annotated
 * for it rather than relying on a global setting — the rest of the API writes its nulls out. The
 * reason is that here the difference carries meaning: absent is "nobody asked", {@code []} is
 * "counted, and nothing matched", and a client that cannot tell those apart shows an empty sidebar
 * where there should be none. Twenty listings that can never carry counts also have no business
 * carrying an empty field for them. Decided with the owner on 2026-09-14; 08 §8.2 says the same.
 *
 * @param data the rows
 * @param page where the next ones are, and roughly how many there are in total
 * @param meta what was true of the answer itself rather than of the rows
 * @param facets how many rows each value of a counted dimension holds, or {@code null} when none
 *     were asked for — which is every listing but a search that carried {@code facet}. Omitted
 *     from the JSON when null, which is what tells "nobody asked" from "nothing matched"
 * @param <T> what the rows are
 */
public record Page<T>(
    List<T> data,
    PageInfo page,
    Meta meta,
    @JsonInclude(JsonInclude.Include.NON_NULL) List<Facet> facets) {

  /**
   * Copies the rows, so a page cannot be changed behind a caller's back.
   *
   * @throws NullPointerException when any part is missing
   */
  public Page {
    data = List.copyOf(Objects.requireNonNull(data, "A page has rows, possibly none"));
    Objects.requireNonNull(page, "A page says where the next one is");
    Objects.requireNonNull(meta, "A page says what was true of the answer");
    // Null and empty are different here, and the difference is visible to a
    // client: null is "nobody asked for counts" and is omitted from the JSON,
    // while an empty list is "counted, and there is nothing to count".
    facets = facets == null ? null : List.copyOf(facets);
  }

  /**
   * A page of rows with a cursor and nothing else known.
   *
   * <p>What almost every listing needs: {@code hasMore} follows from the cursor, the total is not
   * counted, nothing is degraded and the duration is filled in at the edge.
   *
   * @param data the rows
   * @param nextCursor the cursor for the following page, or {@code null} when this was the last
   * @param <T> what the rows are
   * @return the page
   */
  public static <T> Page<T> of(List<T> data, String nextCursor) {
    return new Page<>(data, new PageInfo(nextCursor, nextCursor != null, null), Meta.SOUND, null);
  }

  /**
   * A page that also knows roughly how many rows there are altogether.
   *
   * @param data the rows
   * @param nextCursor the cursor for the following page, or {@code null} when this was the last
   * @param estimatedTotal how many rows the whole collection holds, as an estimate
   * @param <T> what the rows are
   * @return the page
   */
  public static <T> Page<T> of(List<T> data, String nextCursor, long estimatedTotal) {
    return new Page<>(
        data, new PageInfo(nextCursor, nextCursor != null, estimatedTotal), Meta.SOUND, null);
  }

  /**
   * The cursor for the following page, without reaching through {@link #page()}.
   *
   * <p>A convenience and not a second truth: it returns exactly what {@code page().nextCursor()}
   * does, and exists because "give me the next cursor" is what almost every caller wants.
   *
   * @return the cursor, or {@code null} when this was the last page
   */
  public String nextCursor() {
    return page.nextCursor();
  }

  /**
   * The same page, answered by a fallback rather than by the store that usually answers.
   *
   * @param reason a token from {@code docs/reference/degraded-reasons.yaml}; clients branch on it
   *     and never on prose
   * @return a page whose {@code meta} says so
   * @throws IllegalArgumentException when no reason is given, because "degraded, but I shall not say
   *     why" is not something a client can act on
   */
  public Page<T> degraded(String reason) {
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("A degraded answer names the reason it is degraded");
    }
    return new Page<>(data, page, new Meta(true, reason, meta.took()), facets);
  }

  /**
   * The same page, with the time it took to produce.
   *
   * @param took milliseconds
   * @return a page whose {@code meta} says how long it took
   */
  public Page<T> tookMillis(long took) {
    return new Page<>(data, page, new Meta(meta.degraded(), meta.degradedReason(), took), facets);
  }

  /**
   * The same page, carrying the counts somebody asked for (REQ-SRCH-002).
   *
   * @param counted one facet per dimension the request named, in the order it named them
   * @return a page whose {@code facets} member holds them
   * @throws NullPointerException when no list is given — a page with no counts keeps {@code null},
   *     which is what this method is not for
   */
  public Page<T> withFacets(List<Facet> counted) {
    return new Page<>(
        data, page, meta, Objects.requireNonNull(counted, "Use null facets, not withFacets(null)"));
  }

  /**
   * Where the rest of the collection is.
   *
   * @param nextCursor an opaque, signed cursor for the following page, or {@code null} when this was
   *     the last. Never an offset: offsets skip and duplicate rows on data that changes under them
   * @param hasMore whether a following page exists. Derived from the cursor rather than passed, so
   *     the two cannot disagree
   * @param estimatedTotal how many rows the whole collection holds, or {@code null} when nobody
   *     counted. Explicitly an estimate — an exact total over a million rows costs more than it is
   *     worth (08 §8.2) — and absent rather than wrong where counting is not cheap
   */
  public record PageInfo(@Nullable String nextCursor, boolean hasMore, @Nullable Long estimatedTotal) {}

  /**
   * What was true of the answer rather than of the rows.
   *
   * @param degraded whether a derived store was unavailable and something else answered
   *     (ADR-0039). Never a header: {@code Warning: 199} was obsoleted by RFC 9111 §5.5
   * @param degradedReason a stable token from {@code docs/reference/degraded-reasons.yaml}, or
   *     {@code null} when nothing is degraded
   * @param took how many milliseconds the request took, or {@code null} before the edge fills it in
   */
  public record Meta(boolean degraded, @Nullable String degradedReason, @Nullable Long took) {

    /** Nothing wrong, and nothing measured yet. What a block returns before the edge sees it. */
    public static final Meta SOUND = new Meta(false, null, null);
  }
}
