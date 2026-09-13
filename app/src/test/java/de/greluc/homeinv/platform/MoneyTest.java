/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link Money} keeps every promise REQ-NFR-070 makes about it.
 *
 * <p>Held at 100 % branch coverage, which the build enforces for this one class. That is not a
 * target picked for its roundness: the type is a hundred and fifty lines carrying every monetary
 * invariant in the system, and a branch of it nobody exercises is an invariant nobody checked.
 */
@DisplayName("Money")
class MoneyTest {

  private static final Currency EUR = Currency.getInstance("EUR");
  private static final Currency USD = Currency.getInstance("USD");
  private static final Currency JPY = Currency.getInstance("JPY");

  @Test
  @DisplayName("normalises to the currency's scale, so 49.9 and 49.90 are one value")
  void scaleIsTheCurrencys() {
    assertThat(Money.of("49.9", EUR)).isEqualTo(Money.of("49.90", EUR));
    assertThat(Money.of("49.9", EUR)).hasSameHashCodeAs(Money.of("49.90", EUR));
    assertThat(Money.of("49.9", EUR).amountAsText()).isEqualTo("49.90");

    // Yen has no minor unit, and a dinar has three.
    assertThat(Money.of("1000", JPY).amountAsText()).isEqualTo("1000");
    assertThat(Money.of("2.5", Currency.getInstance("KWD")).amountAsText()).isEqualTo("2.500");

    // More digits than the currency has are rounded half up on construction:
    // this is not arithmetic, it is the currency saying how many digits it has.
    assertThat(Money.of("1.005", EUR).amountAsText()).isEqualTo("1.01");
    assertThat(Money.of("1.004", EUR).amountAsText()).isEqualTo("1.00");
  }

  @Test
  @DisplayName("adds and subtracts within one currency")
  void arithmeticWithinACurrency() {
    Money ten = Money.of("10.00", EUR);
    Money three = Money.of("3.50", EUR);

    assertThat(ten.plus(three)).isEqualTo(Money.of("13.50", EUR));
    assertThat(ten.minus(three)).isEqualTo(Money.of("6.50", EUR));
    assertThat(three.minus(ten)).isEqualTo(Money.of("-6.50", EUR));
    assertThat(Money.zero(EUR).plus(ten)).isEqualTo(ten);
  }

  @Test
  @DisplayName("throws across currencies rather than inventing a rate")
  void arithmeticAcrossCurrenciesThrows() {
    Money euros = Money.of("10.00", EUR);
    Money dollars = Money.of("10.00", USD);

    assertThatThrownBy(() -> euros.plus(dollars))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("EUR")
        .hasMessageContaining("USD");
    assertThatThrownBy(() -> euros.minus(dollars)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> euros.isGreaterThan(dollars))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> euros.plus(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName("is not Comparable, so a mixed-currency list cannot be sorted at all")
  void notComparable() {
    // By construction rather than by exception: there is no comparator to reach
    // for, so the mistake cannot be made and then discovered in production.
    assertThat(Comparable.class.isAssignableFrom(Money.class)).isFalse();

    assertThat(Money.of("10.00", EUR).isGreaterThan(Money.of("9.99", EUR))).isTrue();
    assertThat(Money.of("9.99", EUR).isGreaterThan(Money.of("10.00", EUR))).isFalse();
    assertThat(Money.of("10.00", EUR).isGreaterThan(Money.of("10.00", EUR))).isFalse();
  }

  @Test
  @DisplayName("rounds only where the caller says how")
  void multiplicationRoundsExplicitly() {
    Money price = Money.of("100.00", EUR);
    // 33.355 exactly: the halfway case, where HALF_UP and HALF_DOWN part company
    // and UP and DOWN each go their own way. A factor whose product landed on a
    // whole cent would prove nothing about rounding at all.
    BigDecimal factor = new BigDecimal("0.33355");

    assertThat(price.multipliedBy(factor, RoundingMode.HALF_UP)).isEqualTo(Money.of("33.36", EUR));
    assertThat(price.multipliedBy(factor, RoundingMode.HALF_DOWN)).isEqualTo(Money.of("33.35", EUR));
    assertThat(price.multipliedBy(factor, RoundingMode.UP)).isEqualTo(Money.of("33.36", EUR));
    assertThat(price.multipliedBy(factor, RoundingMode.DOWN)).isEqualTo(Money.of("33.35", EUR));

    // There is no overload that guesses a mode, and null is not one.
    assertThatThrownBy(() -> price.multipliedBy(factor, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> price.multipliedBy(null, RoundingMode.HALF_UP))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName("answers the three questions a total asks of it")
  void signAndText() {
    assertThat(Money.zero(EUR).isZero()).isTrue();
    assertThat(Money.of("0.001", EUR).isZero()).isTrue();
    assertThat(Money.of("0.01", EUR).isZero()).isFalse();
    assertThat(Money.of("-0.01", EUR).isNegative()).isTrue();
    assertThat(Money.of("0.00", EUR).isNegative()).isFalse();
    assertThat(Money.of("0.01", EUR).isNegative()).isFalse();

    // A plain string, never scientific notation: this is what goes into JSON.
    assertThat(Money.of("1E+3", EUR).amountAsText()).isEqualTo("1000.00");
    assertThat(Money.of("12.34", EUR).currencyCode()).isEqualTo("EUR");
    assertThat(Money.of("12.34", EUR)).hasToString("12.34 EUR");
  }

  @Test
  @DisplayName("refuses what is not an amount and what is not a currency")
  void refusesNonsense() {
    assertThatThrownBy(() -> Money.of("not a number", "EUR"))
        .isInstanceOf(NumberFormatException.class);
    assertThatThrownBy(() -> Money.of("1.00", "EURO")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Money(null, EUR)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new Money(BigDecimal.ONE, null))
        .isInstanceOf(NullPointerException.class);

    // The string factory and the currency factory agree.
    assertThat(Money.of("7.00", "EUR")).isEqualTo(Money.of("7.00", EUR));
  }
}
