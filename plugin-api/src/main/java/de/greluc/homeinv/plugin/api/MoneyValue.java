/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: Apache-2.0
 */
package de.greluc.homeinv.plugin.api;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

/**
 * An amount and the currency it is in, as a plugin states it.
 *
 * <p><b>Not the core's {@code Money}</b>, which is AGPL and has arithmetic on it. This is the wire
 * shape: a plugin says what it found, the core turns it into its own type and does the arithmetic
 * there, where the rule that totals are per currency lives (ADR-0025). Duplicating the arithmetic
 * here would put a second answer to "what is 1/3 of €10" into the system.
 *
 * <p>{@code double} and {@code float} are absent on purpose and must stay absent. A valuation that
 * arrives as a binary fraction has already lost the cents.
 *
 * @param amount the amount, unrounded as the source gave it. The core rounds it to the currency's
 *     own number of digits, because that is the currency's statement and not the plugin's
 * @param currency the ISO 4217 currency. A plugin that cannot say which currency a number is in has
 *     not produced a valuation
 */
public record MoneyValue(BigDecimal amount, Currency currency) {

  /**
   * Checks that both halves are there.
   *
   * @throws NullPointerException when either is absent
   */
  public MoneyValue {
    Objects.requireNonNull(amount, "An amount is required");
    Objects.requireNonNull(currency, "A currency is required: a bare number is not money");
  }

  /**
   * Builds one from the two strings a wire format carries.
   *
   * @param amount the amount in decimal notation, for example {@code "129.99"}
   * @param currency the ISO 4217 code, for example {@code "EUR"}
   * @return the value
   * @throws NumberFormatException when the amount is not a decimal number
   * @throws IllegalArgumentException when the code is not an ISO 4217 currency
   */
  public static MoneyValue of(String amount, String currency) {
    return new MoneyValue(new BigDecimal(amount), Currency.getInstance(currency));
  }
}
