/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.platform;

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
 * @param data the rows
 * @param page where the next ones are, and roughly how many there are in total
 * @param meta what was true of the answer itself rather than of the rows
 * @param <T> what the rows are
 */
public record Page<T>(List<T> data, PageInfo page, Meta meta) {

  /**
   * Copies the rows, so a page cannot be changed behind a caller's back.
   *
   * @throws NullPointerException when any part is missing
   */
  public Page {
    data = List.copyOf(Objects.requireNonNull(data, "A page has rows, possibly none"));
    Objects.requireNonNull(page, "A page says where the next one is");
    Objects.requireNonNull(meta, "A page says what was true of the answer");
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
    return new Page<>(data, new PageInfo(nextCursor, nextCursor != null, null), Meta.SOUND);
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
        data, new PageInfo(nextCursor, nextCursor != null, estimatedTotal), Meta.SOUND);
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
    return new Page<>(data, page, new Meta(true, reason, meta.took()));
  }

  /**
   * The same page, with the time it took to produce.
   *
   * @param took milliseconds
   * @return a page whose {@code meta} says how long it took
   */
  public Page<T> tookMillis(long took) {
    return new Page<>(data, page, new Meta(meta.degraded(), meta.degradedReason(), took));
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
  public record PageInfo(String nextCursor, boolean hasMore, Long estimatedTotal) {}

  /**
   * What was true of the answer rather than of the rows.
   *
   * @param degraded whether a derived store was unavailable and something else answered
   *     (ADR-0039). Never a header: {@code Warning: 199} was obsoleted by RFC 9111 §5.5
   * @param degradedReason a stable token from {@code docs/reference/degraded-reasons.yaml}, or
   *     {@code null} when nothing is degraded
   * @param took how many milliseconds the request took, or {@code null} before the edge fills it in
   */
  public record Meta(boolean degraded, String degradedReason, Long took) {

    /** Nothing wrong, and nothing measured yet. What a block returns before the edge sees it. */
    public static final Meta SOUND = new Meta(false, null, null);
  }
}
