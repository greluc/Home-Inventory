/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.api;

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
 */
public record Valuation(
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

  /** Where a figure came from (REQ-LIFE-014). */
  public enum Provenance {
    /** Somebody typed it. */
    MANUAL,
    /** A valuation plugin determined it (REQ-LIFE-009). */
    PLUGIN
  }

  /** Nothing recorded, which is what every item starts with. */
  public static final Valuation NONE =
      new Valuation(null, null, null, null, false, null, null, null, null, null);

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
