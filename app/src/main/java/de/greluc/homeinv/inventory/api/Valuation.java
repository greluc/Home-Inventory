/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.api;

import jakarta.annotation.Nullable;
import de.greluc.homeinv.platform.Money;
import java.time.LocalDate;

/**
 * What an item cost, what covers it, and what it would cost to replace (REQ-LIFE-001/002/014).
 *
 * <h2>Three figures, never derived from one another</h2>
 *
 * <p>{@code REQ-LIFE-014} says it about the replacement value in as many words — "independent of
 * the current value and never derived from it" — and it holds for all three. A camera bought for
 * 900 may be worth 200 second-hand and cost 1,100 to buy new today; an insurer asks for the third
 * number, a seller for the second and the tax office for the first. Each carries its own as-of
 * date, because a valuation without one is a number of unknown age.
 *
 * <p>Columns on the item rather than fields of a type version, decided with the owner on
 * 2026-09-13: {@code REQ-LIFE-015} asks for totals per location, type and tag across <em>every</em>
 * type, and an attribute exists only where its type happened to declare it, under whatever name
 * that type chose.
 *
 * @param purchase what it cost, or {@code null}
 * @param purchasedOn when it was bought, or {@code null}
 * @param purchaseSource where from — a shop, a person, a listing — or {@code null}. Free text: an
 *     invoice is an attachment like any other and needs no field of its own
 * @param warrantyUntil when the warranty ends, or {@code null}
 * @param lifetimeWarranty whether it is covered for life, in which case {@code warrantyUntil} is
 *     {@code null}. A flag rather than a date far in the future, because "2099-12-31" is a date
 *     somebody would eventually have to explain
 * @param replacement what it would cost to replace, or {@code null}
 * @param replacementAsOf the day that figure was true, or {@code null}
 * @param replacementSource who said so — {@link Provenance#MANUAL} or {@link Provenance#PLUGIN} —
 *     or {@code null}
 * @param currentValue what it is worth now, or {@code null} (REQ-LIFE-009)
 * @param currentValueAsOf the day that figure was true, or {@code null}
 * @param currentValueSource who says so — {@link Provenance#MANUAL}, {@link
 *     Provenance#DEPRECIATION} or {@link Provenance#PLUGIN} — or {@code null}. Not decoration: the
 *     refresh run rewrites only what it wrote, so a figure somebody typed survives the night
 */
public record Valuation(
    @Nullable Money purchase,
    @Nullable LocalDate purchasedOn,
    @Nullable String purchaseSource,
    @Nullable LocalDate warrantyUntil,
    @Nullable boolean lifetimeWarranty,
    @Nullable Money replacement,
    @Nullable LocalDate replacementAsOf,
    @Nullable Provenance replacementSource,
    @Nullable Money currentValue,
    @Nullable LocalDate currentValueAsOf,
    @Nullable Provenance currentValueSource) {

  /**
   * A valuation that says nothing about where its current value came from.
   *
   * <p>For every caller that has a figure and no opinion about its provenance — a test, an import,
   * a request body, which is most of them. Whoever <b>stores</b> it decides: a number that differs
   * from what is there was typed by somebody, and a number that does not is the number that was
   * already there. {@code Item} holds that rule, in one place, because a provenance decided in two
   * places is a provenance that disagrees with itself.
   *
   * @param purchase what it cost
   * @param purchasedOn when it was bought
   * @param purchaseSource who from
   * @param warrantyUntil when the warranty ends
   * @param lifetimeWarranty whether it is covered for life
   * @param replacement what replacing it would cost
   * @param replacementAsOf the day that figure was true
   * @param replacementSource who said so
   * @param currentValue what it is worth now
   * @param currentValueAsOf the day that figure was true
   */
  public Valuation(
      Money purchase,
      LocalDate purchasedOn,
      String purchaseSource,
      LocalDate warrantyUntil,
      boolean lifetimeWarranty,
      Money replacement,
      LocalDate replacementAsOf,
      Provenance replacementSource,
      Money currentValue,
      LocalDate currentValueAsOf) {
    this(
        purchase,
        purchasedOn,
        purchaseSource,
        warrantyUntil,
        lifetimeWarranty,
        replacement,
        replacementAsOf,
        replacementSource,
        currentValue,
        currentValueAsOf,
        null);
  }

  /** Where a figure came from (REQ-LIFE-014). */
  public enum Provenance {
    /** Somebody typed it. */
    MANUAL,
    /**
     * This application depreciated it, straight-line over the useful life of the item's type
     * (REQ-LIFE-009).
     *
     * <p>The one provenance a refresh run may overwrite, which is the whole reason the three are
     * told apart.
     */
    DEPRECIATION,
    /** A valuation plugin determined it (REQ-LIFE-009). */
    PLUGIN
  }

  /** Nothing recorded, which is what every item starts with. */
  public static final Valuation NONE =
      new Valuation(null, null, null, null, false, null, null, null, null, null, null);

  /**
   * Refuses the one combination that is two answers to one question.
   *
   * @param purchase what it cost
   * @param purchasedOn when
   * @param purchaseSource where from
   * @param warrantyUntil when the warranty ends
   * @param lifetimeWarranty whether it is covered for life
   * @param replacement what replacing it would cost
   * @param replacementAsOf when that was true
   * @param replacementSource who said so
   * @param currentValue what it is worth
   * @param currentValueAsOf when that was true
   */
  public Valuation {
    if (lifetimeWarranty && warrantyUntil != null) {
      throw new IllegalArgumentException(
          "A warranty either ends on a date or lasts for life. Both is two answers to one "
              + "question, and the database refuses the row as well.");
    }
  }
}
