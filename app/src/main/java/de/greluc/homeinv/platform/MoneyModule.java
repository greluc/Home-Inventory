/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.platform;

import java.math.BigDecimal;
import java.util.Currency;
import org.springframework.stereotype.Component;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;

/**
 * How {@link Money} looks in JSON: {@code {"amount":"49.90","currency":"EUR"}} (REQ-NFR-070).
 *
 * <h2>The amount is a string, and that is the whole point</h2>
 *
 * <p>A JSON number is a {@code double} by the time it has been through a browser — {@code
 * JSON.parse} has one numeric type and it is binary floating point. An amount that travelled as a
 * number would come back as {@code 49.900000000000006} often enough to be noticed and rarely enough
 * to be dismissed. As a string it survives the round trip exactly, and a client that wants to do
 * arithmetic with it has to say so.
 *
 * <p>Written by hand rather than left to the record's default shape, which would serialise the
 * {@link BigDecimal} as a number and the {@link Currency} as an object.
 */
@Component
public class MoneyModule extends SimpleModule {

  private static final long serialVersionUID = 1L;

  /** Registers the two halves. */
  public MoneyModule() {
    addSerializer(Money.class, new Serializer());
    addDeserializer(Money.class, new Deserializer());
  }

  /** {@code {"amount":"49.90","currency":"EUR"}}, amount first because that is what is read. */
  private static final class Serializer extends ValueSerializer<Money> {

    @Override
    public void serialize(Money value, JsonGenerator generator, SerializationContext context) {
      generator.writeStartObject();
      generator.writeStringProperty("amount", value.amountAsText());
      generator.writeStringProperty("currency", value.currencyCode());
      generator.writeEndObject();
    }
  }

  /**
   * Reads the same shape back, and refuses a JSON number.
   *
   * <p>Refusing is deliberate: a client sending {@code 49.9} as a number has already lost whatever
   * precision its own runtime lost, and accepting it would make the string rule advisory. The
   * message says what to send instead.
   */
  private static final class Deserializer extends ValueDeserializer<Money> {

    @Override
    public Money deserialize(JsonParser parser, DeserializationContext context) {
      JsonNode node = context.readTree(parser);
      JsonNode amount = node.get("amount");
      JsonNode currency = node.get("currency");
      if (amount == null || currency == null) {
        throw new IllegalArgumentException(
            "An amount of money is {\"amount\":\"49.90\",\"currency\":\"EUR\"}; both fields are "
                + "required.");
      }
      if (!amount.isString()) {
        throw new IllegalArgumentException(
            "An amount travels as a string, not as a JSON number: a number is a double by the "
                + "time it has been through a browser, and 49.90 does not survive that.");
      }
      return new Money(
          new BigDecimal(amount.asString()), Currency.getInstance(currency.asString()));
    }
  }
}
