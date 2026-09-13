/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.platform;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * An amount of one currency (ADR-0025, REQ-NFR-070).
 *
 * <h2>Why this exists rather than a library</h2>
 *
 * <p>JavaMoney was evaluated and rejected for a reason that has nothing to do with its API: its
 * conversion modules schedule background fetches to ECB and IMF endpoints, discovered through
 * {@code ServiceLoader}, so whether this application makes outbound calls would be decided by what
 * happened to be on the classpath. The core has no outbound route to the internet
 * ({@code REQ-PRIV-003}), and a rule that a dependency can quietly break is not a rule.
 *
 * <p>Joda-Money brings its own ISO 4217 data, which can drift from the JDK's. {@link Currency} is
 * the authoritative source and ships with the platform, so the whole of this type is the hundred
 * and fifty lines below.
 *
 * <h2>What it refuses</h2>
 *
 * <ul>
 *   <li><b>No {@code double}, ever.</b> Not in a constructor, not in a factory, not in a test
 *       helper. An architecture rule enforces it across the whole application, because "we are
 *       careful about that" is not a mechanism.
 *   <li><b>Arithmetic across currencies throws.</b> Ten euros plus ten dollars is not twenty of
 *       anything, and the alternative — converting at some rate, from somewhere — is the outbound
 *       call this project does not make.
 *   <li><b>Not {@link Comparable}.</b> Sorting a mixed-currency collection is then impossible by
 *       construction rather than by an exception somebody meets at run time; a currency-aware
 *       comparator is the caller's to choose when it knows they are all the same.
 *   <li><b>Rounding is always explicit.</b> {@link #multipliedBy} takes a {@link RoundingMode} and
 *       has no overload that guesses one.
 * </ul>
 *
 * <h2>Scale, and why equality works the way it does</h2>
 *
 * <p>The amount is normalised on construction to the currency's fraction digits — two for EUR,
 * zero for JPY, three for KWD. So {@code Money.of("49.9", EUR)} and {@code Money.of("49.90", EUR)}
 * are the same value, are {@code equal} and hash alike, which is what a person means by it and what
 * {@code BigDecimal.equals} would deny.
 *
 * @param amount the amount, at the currency's scale
 * @param currency the currency
 */
public record Money(BigDecimal amount, Currency currency) {

  /**
   * Normalises the amount and refuses what cannot be one.
   *
   * @param amount the amount
   * @param currency the currency
   */
  public Money {
    Objects.requireNonNull(amount, "an amount is required");
    Objects.requireNonNull(currency, "a currency is required");
    // HALF_UP rather than an argument, and it is the one place rounding is not
    // the caller's to choose: this is not arithmetic, it is the currency saying
    // how many digits it has. A value arriving with more of them is a value
    // somebody typed, and rounding it half up is what a person expects of it.
    amount = amount.setScale(currency.getDefaultFractionDigits(), RoundingMode.HALF_UP);
  }

  /**
   * An amount written as text, which is how it travels.
   *
   * <p>A string and never a JSON number: a number is a {@code double} by the time it has been
   * through a browser, and {@code 0.1 + 0.2} is where that ends up.
   *
   * @param amount the amount, as a decimal string
   * @param currency the ISO 4217 code
   * @return the value
   * @throws NumberFormatException when the text is not a decimal
   * @throws IllegalArgumentException when the code is not one ISO 4217 knows
   */
  public static Money of(String amount, String currency) {
    return new Money(new BigDecimal(amount), Currency.getInstance(currency));
  }

  /**
   * An amount of a currency already resolved.
   *
   * @param amount the amount, as a decimal string
   * @param currency the currency
   * @return the value
   */
  public static Money of(String amount, Currency currency) {
    return new Money(new BigDecimal(amount), currency);
  }

  /**
   * Nothing, in a currency.
   *
   * <p>Needed by every total: a sum over no items is zero of the currency being asked about, not an
   * absent value that each caller then has to decide what to do with.
   *
   * @param currency the currency
   * @return zero
   */
  public static Money zero(Currency currency) {
    return new Money(BigDecimal.ZERO, currency);
  }

  /**
   * The sum of two amounts of the same currency.
   *
   * @param other the other amount
   * @return the sum
   * @throws IllegalArgumentException when the currencies differ
   */
  public Money plus(Money other) {
    requireSameCurrency(other);
    return new Money(amount.add(other.amount), currency);
  }

  /**
   * The difference of two amounts of the same currency.
   *
   * @param other the other amount
   * @return the difference, which may be negative
   * @throws IllegalArgumentException when the currencies differ
   */
  public Money minus(Money other) {
    requireSameCurrency(other);
    return new Money(amount.subtract(other.amount), currency);
  }

  /**
   * This amount scaled by a factor, rounded as the caller says.
   *
   * <p>The one operation that has to round, because a factor is not a currency: straight-line
   * depreciation over a useful life is exactly this, and the rounding mode is the caller's decision
   * to make and to be able to name afterwards (REQ-LIFE-009).
   *
   * @param factor the factor
   * @param rounding how to round the result to the currency's scale
   * @return the scaled amount
   */
  public Money multipliedBy(BigDecimal factor, RoundingMode rounding) {
    Objects.requireNonNull(factor, "a factor is required");
    Objects.requireNonNull(rounding, "a rounding mode is required; none is assumed");
    return new Money(
        amount.multiply(factor).setScale(currency.getDefaultFractionDigits(), rounding), currency);
  }

  /**
   * Whether this amount is greater than another of the same currency.
   *
   * <p>A method and not {@link Comparable}: a comparator would let a mixed-currency list be sorted,
   * and a list sorted by a number whose currencies differ is worse than one that refused to sort.
   *
   * @param other the other amount
   * @return true when this one is the larger
   * @throws IllegalArgumentException when the currencies differ
   */
  public boolean isGreaterThan(Money other) {
    requireSameCurrency(other);
    return amount.compareTo(other.amount) > 0;
  }

  /**
   * Whether this amount is zero.
   *
   * @return true when nothing
   */
  public boolean isZero() {
    return amount.signum() == 0;
  }

  /**
   * Whether this amount is below zero.
   *
   * @return true when negative
   */
  public boolean isNegative() {
    return amount.signum() < 0;
  }

  /**
   * The amount as it travels: a plain decimal string at the currency's scale.
   *
   * @return the amount, never in scientific notation
   */
  public String amountAsText() {
    return amount.toPlainString();
  }

  /**
   * The ISO 4217 code.
   *
   * @return the code, three letters
   */
  public String currencyCode() {
    return currency.getCurrencyCode();
  }

  /**
   * Refuses an operation between two currencies.
   *
   * @param other the other amount
   * @throws IllegalArgumentException when the currencies differ
   */
  private void requireSameCurrency(Money other) {
    Objects.requireNonNull(other, "an amount is required");
    if (!currency.equals(other.currency)) {
      throw new IllegalArgumentException(
          "Cannot combine "
              + currencyCode()
              + " with "
              + other.currencyCode()
              + ": there is no rate here to convert with, and inventing one would be a total that "
              + "is wrong in a way nobody can see. Total per currency instead.");
    }
  }

  /**
   * The amount and its code, for a log line or a message.
   *
   * <p>Not for a person to read in the interface: what a reader sees is formatted by the client in
   * their own locale, which is where the knowledge of how a number looks belongs (ADR-0025).
   *
   * @return {@code 49.90 EUR}
   */
  @Override
  public String toString() {
    return amountAsText() + " " + currencyCode();
  }
}
