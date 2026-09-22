/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.graphql;

import graphql.GraphQLContext;
import graphql.execution.CoercedVariables;
import graphql.language.StringValue;
import graphql.language.Value;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import graphql.schema.GraphQLScalarType;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.execution.RuntimeWiringConfigurer;

/**
 * The two scalars the schema declares, and nothing else (REQ-API-006).
 *
 * <p>Both exist because the alternatives are wrong rather than because they are convenient.
 *
 * <p><b>{@code JSON}</b> carries an item's attributes. They cannot be a typed GraphQL object: what
 * keys an item has is decided by its type version at run time, by the tenant, and a schema that
 * described them would have to be regenerated whenever somebody added a field — which is exactly
 * the "no DDL at run time" line ADR-0004 draws, moved into the API. The attributes are stored as
 * JSON text (ADR-0004) and this scalar hands them out parsed, so a client receives an object rather
 * than a string containing one.
 *
 * <p><b>{@code DateTime}</b> is an instant in UTC. GraphQL has no date type, and the alternative —
 * a {@code String} — would leave the format to each resolver, which is how two endpoints end up
 * disagreeing about a timezone (REQ-NFR-035).
 *
 * <p>There is deliberately <b>no {@code BigDecimal} scalar</b>. A quantity and an amount travel as
 * {@code String}, because GraphQL's {@code Float} is a double and this system does not put a
 * quantity or an amount into binary floating point (ADR-0025). A scalar would hide that decision
 * behind a name; a {@code String} states it.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class GraphQlConfiguration {

  /**
   * Registers the scalars with the runtime.
   *
   * @param json the mapper the application already uses, so the JSON a GraphQL client receives is
   *     parsed by the same rules as the JSON a REST client sends
   * @return the configurer
   */
  @Bean
  RuntimeWiringConfigurer scalars(ObjectMapper json) {
    return wiring -> wiring.scalar(jsonScalar(json)).scalar(dateTimeScalar());
  }

  /**
   * The {@code JSON} scalar.
   *
   * <p>Serialises a {@code String} by parsing it — the attributes arrive from {@code inventory} as
   * JSON text — and any other object as itself. Input coercion refuses everything: this is a
   * read-only surface, so a {@code JSON} value never travels inwards, and a scalar that could parse
   * one would be a way to smuggle an unvalidated document into a resolver.
   *
   * @param json the mapper
   * @return the scalar
   */
  private static GraphQLScalarType jsonScalar(ObjectMapper json) {
    return GraphQLScalarType.newScalar()
        .name("JSON")
        .description("An attribute map, exactly as ADR-0004 stores it.")
        .coercing(
            new Coercing<Object, Object>() {
              @Override
              public Object serialize(
                  Object dataFetcherResult, GraphQLContext context, Locale locale) {
                if (!(dataFetcherResult instanceof String text)) {
                  return dataFetcherResult;
                }
                if (text.isBlank()) {
                  return Map.of();
                }
                try {
                  return json.readValue(text, Object.class);
                } catch (JacksonException notJson) {
                  // The column holds JSONB and cannot contain anything else, so
                  // this is a wiring fault rather than bad data. Said plainly,
                  // because an empty object here would look like an item with no
                  // attributes.
                  throw new CoercingSerializeException(
                      "A stored attribute document could not be read as JSON", notJson);
                }
              }

              @Override
              public Object parseValue(Object input, GraphQLContext context, Locale locale) {
                throw new CoercingParseValueException(
                    "JSON is an output type on this surface. Nothing is written through GraphQL"
                        + " (ADR-0010), so there is no argument that takes one.");
              }

              @Override
              public Object parseLiteral(
                  Value<?> input, CoercedVariables variables, GraphQLContext context,
                  Locale locale) {
                throw new CoercingParseValueException(
                    "JSON is an output type on this surface.");
              }
            })
        .build();
  }

  /**
   * The {@code DateTime} scalar: an {@link Instant}, ISO-8601, in UTC.
   *
   * @return the scalar
   */
  private static GraphQLScalarType dateTimeScalar() {
    return GraphQLScalarType.newScalar()
        .name("DateTime")
        .description("An instant in UTC, ISO-8601.")
        .coercing(
            new Coercing<Instant, String>() {
              @Override
              public String serialize(
                  Object dataFetcherResult, GraphQLContext context, Locale locale) {
                if (dataFetcherResult instanceof Instant instant) {
                  return instant.toString();
                }
                throw new CoercingSerializeException(
                    "DateTime carries an Instant, and was given a "
                        + dataFetcherResult.getClass().getSimpleName());
              }

              @Override
              public Instant parseValue(Object input, GraphQLContext context, Locale locale) {
                try {
                  return Instant.parse(String.valueOf(input));
                } catch (DateTimeParseException notAnInstant) {
                  throw new CoercingParseValueException(
                      "A DateTime is an ISO-8601 instant in UTC, such as 2026-09-21T09:14:02Z",
                      notAnInstant);
                }
              }

              @Override
              public Instant parseLiteral(
                  Value<?> input, CoercedVariables variables, GraphQLContext context,
                  Locale locale) {
                // Bound to a local: two calls to `getValue()` are two values as far
                // as an analyser is concerned, and the second one is the one that
                // would be dereferenced.
                String literal = input instanceof StringValue text ? text.getValue() : null;
                if (literal != null) {
                  return parseValue(literal, context, locale);
                }
                throw new CoercingParseValueException("A DateTime literal is a string");
              }
            })
        .build();
  }
}
