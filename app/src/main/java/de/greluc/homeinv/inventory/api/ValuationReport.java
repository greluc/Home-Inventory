/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.api;

import de.greluc.homeinv.platform.Money;
import java.util.List;
import java.util.UUID;

/**
 * What the things in here are worth, per location, type and tag (REQ-LIFE-008, REQ-LIFE-015,
 * REQ-LIFE-017).
 *
 * <h2>Three figures, never mixed</h2>
 *
 * <p>REQ-LIFE-015 asks for purchase price, current value and replacement value <b>separately and
 * unambiguously labelled</b>, and REQ-LIFE-014 says they are independent and never derived from one
 * another. So a row carries three totals rather than one, and there is no "value" without a
 * qualifier anywhere in this type — a field called {@code total} would be the first place somebody
 * added them together.
 *
 * <h2>Per currency, and it says so</h2>
 *
 * <p>REQ-LIFE-017: every total is per currency, and a set spanning several shows <b>one line per
 * currency</b> with an explicit statement that no conversion took place. A mixed total is never
 * produced, "not even as an approximation" — so each figure is a {@link List} of {@link Money}
 * rather than one amount, and a caller that wants a single number has to decide for itself which
 * currency it means. There is nowhere in this API to ask for one.
 *
 * <h2>Two totals per row, and both are labelled</h2>
 *
 * <p>A location contains locations: a room holds boxes. "What is this room worth" and "what is
 * sitting directly in this room" are different questions with different answers, and a report
 * showing one number would be a number whose meaning the reader has to guess. Decided with the
 * owner on 2026-09-20. For a type or a tag there is no tree and {@link Row#subtree()} repeats
 * {@link Row#own()} rather than being absent, so one shape reads the same whichever dimension it
 * came from.
 */
public interface ValuationReport {

  /**
   * What is in each place, and what is under it.
   *
   * @param root the place to report on, or {@code null} for the whole tenant. A row appears for
   *     every location at or beneath it that holds anything, or has anything beneath it
   * @param limit how many rows at most; capped at 200 (REQ-NFR-010)
   * @return the report
   * @throws de.greluc.homeinv.platform.NotFoundException when this tenant has no such location
   */
  Report byLocation(UUID root, int limit);

  /**
   * What each kind of thing is worth.
   *
   * <p>Grouped by the item <b>type</b> and not by the type version an item was written against: an
   * item written last year is still a power tool, which is the argument 08 §8.2 makes about the
   * {@code type:} filter.
   *
   * @param limit how many rows at most; capped at 200
   * @return the report
   */
  Report byType(int limit);

  /**
   * What the things carrying each tag are worth.
   *
   * <p>An item with three tags counts in three rows. That is what a tag report is for — "what are
   * the valuables worth" is a question about a tag and not a partition — and it is why these rows
   * do <b>not</b> sum to the tenant's total. {@link Report#overlapping()} says so, rather than
   * leaving a reader to discover it by adding the column up.
   *
   * @param limit how many rows at most; capped at 200
   * @return the report
   */
  Report byTag(int limit);

  /**
   * One report.
   *
   * @param dimension {@code location}, {@code type} or {@code tag}
   * @param rows one per group, largest first by the figure that is present
   * @param currencies every currency that appears anywhere in the report, so a client can lay out
   *     columns before reading a row
   * @param converted always {@code false}, and present rather than implied: REQ-LIFE-017 asks the
   *     report to <b>state</b> that no conversion took place, and a field a client can render is a
   *     statement where a missing field is an assumption
   * @param overlapping whether a thing can appear in more than one row, which is true of tags and
   *     false of locations and types. A reader who adds up an overlapping column gets a number
   *     that means nothing, and this is what warns them
   */
  record Report(
      String dimension,
      List<Row> rows,
      List<String> currencies,
      boolean converted,
      boolean overlapping) {}

  /**
   * One group's figures.
   *
   * @param id the location, type or tag
   * @param label what to call it, in the tenant's own words
   * @param own the totals for things directly in this group
   * @param subtree the totals including everything beneath it; equal to {@code own} where the
   *     dimension has no tree
   * @param ownCount how many things are directly in this group
   * @param subtreeCount how many including everything beneath it
   */
  record Row(
      UUID id, String label, Figures own, Figures subtree, long ownCount, long subtreeCount) {}

  /**
   * The three figures, each per currency (REQ-LIFE-015, REQ-LIFE-017).
   *
   * @param purchase what the things cost, per currency
   * @param current what they are worth now, per currency
   * @param replacement what replacing them would cost, per currency
   */
  record Figures(List<Money> purchase, List<Money> current, List<Money> replacement) {

    /** A group with nothing in it. */
    public static final Figures NONE = new Figures(List.of(), List.of(), List.of());

    /**
     * The two sets of figures added together, currency by currency.
     *
     * <p>Used to roll a location's own figures into its ancestors'. Amounts in different currencies
     * are kept apart rather than added — which is the whole point, and the one thing {@link Money}
     * would throw on if this tried to do otherwise.
     *
     * @param other the figures to add
     * @return the sum, per figure and per currency
     */
    public Figures plus(Figures other) {
      return new Figures(
          addPerCurrency(purchase, other.purchase()),
          addPerCurrency(current, other.current()),
          addPerCurrency(replacement, other.replacement()));
    }

    private static List<Money> addPerCurrency(List<Money> one, List<Money> other) {
      java.util.Map<java.util.Currency, java.math.BigDecimal> byCurrency =
          new java.util.TreeMap<>(java.util.Comparator.comparing(java.util.Currency::getCurrencyCode));
      for (Money amount : one) {
        byCurrency.merge(amount.currency(), amount.amount(), java.math.BigDecimal::add);
      }
      for (Money amount : other) {
        byCurrency.merge(amount.currency(), amount.amount(), java.math.BigDecimal::add);
      }
      return byCurrency.entrySet().stream()
          .map(entry -> new Money(entry.getValue(), entry.getKey()))
          .toList();
    }
  }
}
