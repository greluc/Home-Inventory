/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.catalog.application;

import de.greluc.homeinv.catalog.api.FieldConstraints;
import de.greluc.homeinv.catalog.api.FieldDataType;
import de.greluc.homeinv.catalog.api.FieldDefinitionView;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Turns a type version's field definitions into the JSON Schema that version ships (REQ-CORE-027).
 *
 * <h2>Why the document matters more than it looks</h2>
 *
 * <p>It is not documentation. The server validates with this exact document (ADR-0056), clients
 * validate offline with the same bytes, and the acceptance of REQ-CORE-027 is that the two agree. A
 * bug here is therefore a validation bug in the same commit rather than a discrepancy that shows up
 * as "the client let me save something the server refused".
 *
 * <h2>Draft 2020-12, and closed</h2>
 *
 * <p>{@code additionalProperties: false}: an attribute the version does not declare is a rejection,
 * not something to keep. REQ-SEC-029 says so for every request body, and here it is also what makes
 * a deprecated field's values safe — the field stays declared, so its values stay valid, and only
 * input forms stop offering it.
 *
 * <p>No {@code $ref} is ever emitted. The generated document is self-contained, which is what lets
 * the validator run with remote resolution switched off and a client cache one file per version.
 */
@Component
@RequiredArgsConstructor
public class JsonSchemaGenerator {

  /** The dialect REQ-CORE-027 names. */
  public static final String DIALECT = "https://json-schema.org/draft/2020-12/schema";

  /**
   * An exact decimal as text.
   *
   * <p>Money and decimals travel as strings, because a JSON number is a double by the time it
   * reaches a browser and this project does not put arithmetic on binary floating point (ADR-0025).
   * The pattern is what stops "49,90" and "4.9e1" from being stored as if they were the same thing.
   */
  private static final String DECIMAL_PATTERN = "^-?[0-9]+(\\.[0-9]+)?$";

  /** ISO 4217 is three upper-case letters, and nothing else is a currency. */
  private static final String CURRENCY_PATTERN = "^[A-Z]{3}$";

  private final ObjectMapper mapper;

  /**
   * Generates the document for one version.
   *
   * @param typeVersionId the version, which becomes the schema's {@code $id} so that a cached
   *     document names what it describes
   * @param fields every field the version declares, deprecated ones included
   * @param valueListEntries resolves a value list to its permitted values; called once per {@code
   *     enum} or {@code multi-enum} field
   * @return the schema document as JSON text
   */
  public String generate(
      UUID typeVersionId,
      List<FieldDefinitionView> fields,
      Function<UUID, List<String>> valueListEntries) {

    ObjectNode schema = mapper.createObjectNode();
    schema.put("$schema", DIALECT);
    schema.put("$id", "urn:homeinv:type-version:" + typeVersionId);
    schema.put("type", "object");
    schema.put("additionalProperties", false);

    ObjectNode properties = schema.putObject("properties");
    ArrayNode required = mapper.createArrayNode();

    for (FieldDefinitionView field : fields) {
      properties.set(field.key(), property(field, valueListEntries));
      if (field.effectivelyRequired()) {
        required.add(field.key());
      }
    }

    // An empty `required` is legal and says nothing; leaving it out says the same
    // thing in fewer bytes, and every one of these documents is fetched by every
    // client of every tenant.
    if (!required.isEmpty()) {
      schema.set("required", required);
    }
    return mapper.writeValueAsString(schema);
  }

  /**
   * The fragment describing one field's value.
   *
   * @param field the definition
   * @param valueListEntries resolves the permitted values of an enumeration
   * @return the schema node for this property
   */
  private ObjectNode property(
      FieldDefinitionView field, Function<UUID, List<String>> valueListEntries) {

    ObjectNode node = mapper.createObjectNode();
    FieldConstraints limits = field.constraints() == null ? FieldConstraints.NONE : field.constraints();

    switch (field.dataType()) {
      case TEXT, MULTILINE, SECRET -> text(node, limits);
      case URL -> {
        text(node, limits);
        node.put("format", "uri");
      }
      case EMAIL -> {
        text(node, limits);
        node.put("format", "email");
      }
      case INTEGER -> {
        node.put("type", "integer");
        range(node, limits);
      }
      case DECIMAL -> {
        node.put("type", "string");
        node.put("pattern", DECIMAL_PATTERN);
      }
      case BOOLEAN -> node.put("type", "boolean");
      case DATE -> {
        node.put("type", "string");
        node.put("format", "date");
      }
      case DATETIME -> {
        node.put("type", "string");
        node.put("format", "date-time");
      }
      case ENUM -> {
        node.put("type", "string");
        node.set("enum", values(field, valueListEntries));
      }
      case MULTI_ENUM -> {
        node.put("type", "array");
        node.put("uniqueItems", true);
        ObjectNode items = node.putObject("items");
        items.put("type", "string");
        items.set("enum", values(field, valueListEntries));
      }
      case MONEY -> money(node, limits);
      case QUANTITY -> quantity(node, limits);
      case REFERENCE, FILE -> {
        node.put("type", "string");
        node.put("format", "uuid");
      }
    }
    return node;
  }

  /**
   * The shape shared by every text-like kind.
   *
   * @param node the fragment being built
   * @param limits the declared constraints
   */
  private void text(ObjectNode node, FieldConstraints limits) {
    node.put("type", "string");
    if (limits.minLength() != null) {
      node.put("minLength", limits.minLength());
    }
    if (limits.maxLength() != null) {
      node.put("maxLength", limits.maxLength());
    }
    if (limits.pattern() != null) {
      // Anchored, because a tenant writing `[0-9]+` means "digits", not "contains
      // a digit" — and an unanchored pattern accepts everything with one digit in
      // it, which is a constraint that reads as one and is not.
      node.put("pattern", anchored(limits.pattern()));
    }
  }

  /**
   * The numeric bounds, for the one kind whose value is a JSON number.
   *
   * @param node the fragment being built
   * @param limits the declared constraints
   */
  private void range(ObjectNode node, FieldConstraints limits) {
    if (limits.min() != null) {
      node.put("minimum", limits.min());
    }
    if (limits.max() != null) {
      node.put("maximum", limits.max());
    }
  }

  /**
   * An amount and its ISO 4217 code, as one object with no room for anything else.
   *
   * <p>The bounds are not expressed here. They apply to the amount, the amount is a string so that
   * it stays exact, and a range over a string is not a thing JSON Schema can say — so the
   * application checks it after parsing, and this document is the shape.
   *
   * @param node the fragment being built
   * @param limits the declared constraints; a declared {@code unit} fixes the currency
   */
  private void money(ObjectNode node, FieldConstraints limits) {
    node.put("type", "object");
    node.put("additionalProperties", false);
    ObjectNode properties = node.putObject("properties");
    ObjectNode amount = properties.putObject("amount");
    amount.put("type", "string");
    amount.put("pattern", DECIMAL_PATTERN);
    ObjectNode currency = properties.putObject("currency");
    currency.put("type", "string");
    if (limits.unit() != null) {
      // A field that declares its currency accepts that one. REQ-CORE-032 forbids
      // a mixed-currency total, and a field that takes any currency can only be
      // summed per currency — declaring it is how a tenant gets one number back.
      ArrayNode only = mapper.createArrayNode();
      only.add(limits.unit());
      currency.set("enum", only);
    } else {
      currency.put("pattern", CURRENCY_PATTERN);
    }
    ArrayNode required = node.putArray("required");
    required.add("amount");
    required.add("currency");
  }

  /**
   * A number and its unit, shaped like money and for the same reason.
   *
   * @param node the fragment being built
   * @param limits the declared constraints; a declared {@code unit} fixes the unit
   */
  private void quantity(ObjectNode node, FieldConstraints limits) {
    node.put("type", "object");
    node.put("additionalProperties", false);
    ObjectNode properties = node.putObject("properties");
    ObjectNode value = properties.putObject("value");
    value.put("type", "string");
    value.put("pattern", DECIMAL_PATTERN);
    ObjectNode unit = properties.putObject("unit");
    unit.put("type", "string");
    unit.put("minLength", 1);
    if (limits.unit() != null) {
      ArrayNode only = mapper.createArrayNode();
      only.add(limits.unit());
      unit.set("enum", only);
    }
    ArrayNode required = node.putArray("required");
    required.add("value");
    required.add("unit");
  }

  /**
   * The permitted values of an enumeration.
   *
   * @param field the field, which names a value list
   * @param valueListEntries the resolver
   * @return the values, in the list's display order
   */
  private ArrayNode values(
      FieldDefinitionView field, Function<UUID, List<String>> valueListEntries) {
    ArrayNode array = mapper.createArrayNode();
    valueListEntries.apply(field.valueListId()).forEach(array::add);
    return array;
  }

  /**
   * A pattern that matches the whole value.
   *
   * @param pattern what the tenant wrote
   * @return the same pattern anchored at both ends, unchanged when it already is
   */
  private String anchored(String pattern) {
    String anchoredPattern = pattern.startsWith("^") ? pattern : "^" + pattern;
    return anchoredPattern.endsWith("$") ? anchoredPattern : anchoredPattern + "$";
  }
}
