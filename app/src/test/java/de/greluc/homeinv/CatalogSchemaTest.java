/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.homeinv.catalog.api.AttributeValidator;
import de.greluc.homeinv.catalog.api.FieldConstraints;
import de.greluc.homeinv.catalog.api.FieldDataType;
import de.greluc.homeinv.catalog.api.FieldDefinitionView;
import de.greluc.homeinv.catalog.api.TypeRegistry;
import de.greluc.homeinv.catalog.api.VisibilityRule;
import de.greluc.homeinv.catalog.application.JsonSchemaGenerator;
import de.greluc.homeinv.catalog.application.SchemaAttributeValidator;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The generated schema, and the verdict it produces (REQ-CORE-005, REQ-CORE-022, REQ-CORE-027).
 *
 * <h2>Why the two are tested together</h2>
 *
 * <p>ADR-0056 makes the generated document the thing the server validates with, so a generator bug
 * and a validation bug are the same bug. Testing the document's text alone would prove it contains
 * the right words; testing the verdict alone would prove the pair agrees with itself. Each test here
 * generates a schema from a field definition and then asks the validator what it makes of a value,
 * which is the property REQ-CORE-027's acceptance states: what a client checks offline is what the
 * server checks.
 *
 * <p>No database and no container: the registry is a stub holding one document. The queries that
 * read real definitions are covered where they run, against a real schema.
 */
@DisplayName("The type system's schema")
class CatalogSchemaTest {

  private static final UUID VERSION = UUID.fromString("018f2f4a-0000-7000-8000-00000000cafe");
  private static final UUID LIST = UUID.fromString("018f2f4a-0000-7000-8000-0000000015t5".replace('t', 'a'));

  private final JsonSchemaGenerator generator = new JsonSchemaGenerator(JsonMapper.builder().build());

  /**
   * A validator that answers with one prepared document.
   *
   * @param document the schema every version resolves to
   * @return a validator over exactly that document
   */
  private AttributeValidator validatorFor(String document) {
    return new SchemaAttributeValidator(
        new TypeRegistry() {
          @Override
          public java.util.List<TypeRegistry.ExpiryField> expiryFields() {
            // Nothing here is about expiries; the stub answers "none" so the
            // schema assertions stay about what they are about.
            return java.util.List.of();
          }

          @Override
          public UUID publishedItemTypeVersion(UUID itemTypeId) {
            return VERSION;
          }

          @Override
          public java.util.Optional<UUID> itemTypeByKey(String key) {
            return java.util.Optional.empty();
          }

          @Override
          public java.util.Map<UUID, TypeIdentity> typesOfVersions(
              java.util.Collection<UUID> versionIds) {
            // Nor what a version's type is called.
            return java.util.Map.of();
          }

          @Override
          public java.util.List<UUID> itemTypeVersionsByKeys(java.util.Collection<String> keys) {
            // Nor what a type key resolves to.
            return java.util.List.of();
          }

          @Override
          public java.util.List<QueryableField> queryableFields() {
            // This stub exists to answer one schema question; nothing here asks
            // what may be filtered.
            return java.util.List.of();
          }

          @Override
          public UUID publishedCategoryVersion(UUID categoryId) {
            return VERSION;
          }

          @Override
          public UUID categoryOfVersion(UUID categoryVersionId) {
            return VERSION;
          }

          @Override
          public boolean permitsChildCategory(UUID parentCategoryId, UUID childCategoryId) {
            // Unrestricted, which is what a category with no rule is. Nothing in
            // this test moves a location; the method is here because the port has
            // it.
            return true;
          }

          @Override
          public Map<UUID, UUID> categoriesOfVersions(Collection<UUID> ids) {
            return Map.of();
          }

          @Override
          public Map<UUID, UUID> itemTypesOfVersions(Collection<UUID> ids) {
            return Map.of();
          }

          @Override
          public List<FieldDefinitionView> fields(UUID typeVersionId) {
            return List.of();
          }

          @Override
          public String jsonSchema(UUID typeVersionId) {
            return document;
          }

          @Override
          public List<String> valueListEntries(UUID valueListId) {
            return List.of();
          }
        });
  }

  /**
   * A field definition with everything at its default.
   *
   * @param key the attribute key
   * @param type the kind of value
   * @param required whether a value must be present
   * @param constraints the declared limits
   * @return the definition
   */
  private FieldDefinitionView field(
      String key, FieldDataType type, boolean required, FieldConstraints constraints) {
    return new FieldDefinitionView(
        UUID.randomUUID(),
        key,
        type,
        Map.of("en", key),
        Map.of(),
        required,
        null,
        constraints,
        type.needsValueList() ? LIST : null,
        null,
        null,
        0,
        false,
        false,
        false,
        false,
        false, false);
  }

  /**
   * Generates a document for one field and validates a value against it.
   *
   * @param definition the field
   * @param attributes the attribute set as JSON text
   * @param values what the field's value list holds, when it has one
   * @return the violations
   */
  private List<AttributeValidator.Violation> check(
      FieldDefinitionView definition, String attributes, List<String> values) {
    String document = generator.generate(VERSION, List.of(definition), listId -> values);
    return validatorFor(document).validate(VERSION, attributes).violations();
  }

  @Nested
  @DisplayName("names what it describes")
  class Identity {

    @Test
    @DisplayName("as draft 2020-12, closed, with the version as its id")
    void dialectAndIdentity() {
      String document = generator.generate(VERSION, List.of(), id -> List.of());
      assertThat(document)
          .contains("\"$schema\":\"" + JsonSchemaGenerator.DIALECT + "\"")
          .contains("\"$id\":\"urn:homeinv:type-version:" + VERSION + "\"")
          .contains("\"additionalProperties\":false");
    }

    @Test
    @DisplayName("and a type with no fields accepts nothing but an empty set")
    void emptyTypeAcceptsOnlyEmpty() {
      String document = generator.generate(VERSION, List.of(), id -> List.of());
      AttributeValidator validator = validatorFor(document);
      assertThat(validator.validate(VERSION, "{}").valid()).isTrue();
      // `{}` as a schema would accept this. The generated one does not, which is
      // the difference between "no fields declared" and "anything goes".
      assertThat(validator.validate(VERSION, "{\"invented\":1}").valid()).isFalse();
    }
  }

  @Nested
  @DisplayName("refuses what the field does not declare")
  class Refusals {

    @Test
    @DisplayName("an attribute no field declares (REQ-SEC-029)")
    void unknownKey() {
      assertThat(check(field("isbn", FieldDataType.TEXT, false, FieldConstraints.NONE),
              "{\"isbn\":\"9783836287456\",\"smuggled\":\"x\"}", List.of()))
          .isNotEmpty();
    }

    @Test
    @DisplayName("a missing required value, and names it")
    void missingRequired() {
      List<AttributeValidator.Violation> violations =
          check(field("isbn", FieldDataType.TEXT, true, FieldConstraints.NONE), "{}", List.of());
      assertThat(violations).isNotEmpty();
      assertThat(violations.getFirst().message()).contains("isbn");
    }

    @Test
    @DisplayName("a value of the wrong shape, and points at it")
    void wrongType() {
      List<AttributeValidator.Violation> violations =
          check(
              field("published", FieldDataType.INTEGER, false, FieldConstraints.NONE),
              "{\"published\":\"not a number\"}",
              List.of());
      assertThat(violations).isNotEmpty();
      assertThat(violations.getFirst().path()).isEqualTo("/published");
    }

    @Test
    @DisplayName("a number outside the declared range")
    void outOfRange() {
      FieldConstraints range =
          new FieldConstraints(null, BigDecimal.ONE, BigDecimal.TEN, null, null, null, null);
      assertThat(check(field("count", FieldDataType.INTEGER, false, range), "{\"count\":11}", List.of()))
          .isNotEmpty();
      assertThat(check(field("count", FieldDataType.INTEGER, false, range), "{\"count\":5}", List.of()))
          .isEmpty();
    }

    @Test
    @DisplayName("a value the pattern does not match over its whole length")
    void patternIsAnchored() {
      FieldConstraints digits =
          new FieldConstraints("[0-9]+", null, null, null, null, null, null);
      // Unanchored, "abc123" contains digits and would pass. A tenant writing
      // `[0-9]+` means the value is digits, so the generator anchors it.
      assertThat(check(field("code", FieldDataType.TEXT, false, digits), "{\"code\":\"abc123\"}", List.of()))
          .isNotEmpty();
      assertThat(check(field("code", FieldDataType.TEXT, false, digits), "{\"code\":\"123\"}", List.of()))
          .isEmpty();
    }

    @Test
    @DisplayName("a malformed e-mail address, because format asserts here")
    void formatAsserts() {
      assertThat(check(field("owner", FieldDataType.EMAIL, false, FieldConstraints.NONE),
              "{\"owner\":\"not-an-address\"}", List.of()))
          .isNotEmpty();
    }

    @Test
    @DisplayName("a value outside the field's value list")
    void enumeration() {
      FieldDefinitionView condition = field("condition", FieldDataType.ENUM, false, FieldConstraints.NONE);
      assertThat(check(condition, "{\"condition\":\"mint\"}", List.of("new", "used")))
          .isNotEmpty();
      assertThat(check(condition, "{\"condition\":\"used\"}", List.of("new", "used")))
          .isEmpty();
    }
  }

  @Nested
  @DisplayName("keeps money and quantities exact")
  class UnitsAndAmounts {

    @Test
    @DisplayName("money is an amount as text and an ISO 4217 code")
    void moneyShape() {
      FieldDefinitionView price = field("purchasePrice", FieldDataType.MONEY, false, FieldConstraints.NONE);
      assertThat(check(price, "{\"purchasePrice\":{\"amount\":\"49.90\",\"currency\":\"EUR\"}}", List.of()))
          .isEmpty();
      // A JSON number is a double by the time it reaches a browser (ADR-0025).
      assertThat(check(price, "{\"purchasePrice\":{\"amount\":49.90,\"currency\":\"EUR\"}}", List.of()))
          .isNotEmpty();
      assertThat(check(price, "{\"purchasePrice\":{\"amount\":\"49.90\",\"currency\":\"Euro\"}}", List.of()))
          .isNotEmpty();
      assertThat(check(price, "{\"purchasePrice\":{\"amount\":\"49.90\"}}", List.of())).isNotEmpty();
    }

    @Test
    @DisplayName("a declared currency is the only one accepted (REQ-CORE-032)")
    void declaredCurrency() {
      FieldConstraints euros = new FieldConstraints(null, null, null, null, null, "EUR", null);
      FieldDefinitionView price = field("purchasePrice", FieldDataType.MONEY, false, euros);
      assertThat(check(price, "{\"purchasePrice\":{\"amount\":\"1.00\",\"currency\":\"EUR\"}}", List.of()))
          .isEmpty();
      assertThat(check(price, "{\"purchasePrice\":{\"amount\":\"1.00\",\"currency\":\"USD\"}}", List.of()))
          .isNotEmpty();
    }

    @Test
    @DisplayName("a quantity carries its unit and cannot lose it")
    void quantityShape() {
      FieldDefinitionView weight = field("weight", FieldDataType.QUANTITY, false, FieldConstraints.NONE);
      assertThat(check(weight, "{\"weight\":{\"value\":\"2.5\",\"unit\":\"kg\"}}", List.of())).isEmpty();
      assertThat(check(weight, "{\"weight\":{\"value\":\"2.5\"}}", List.of())).isNotEmpty();
      assertThat(check(weight, "{\"weight\":\"2.5 kg\"}", List.of())).isNotEmpty();
    }
  }

  @Nested
  @DisplayName("says what a field means to the rest of the system")
  class Classification {

    @Test
    @DisplayName("a deprecated field is never required (REQ-CORE-026)")
    void deprecatedIsNotRequired() {
      FieldDefinitionView retired =
          new FieldDefinitionView(
              UUID.randomUUID(), "old", FieldDataType.TEXT, Map.of(), Map.of(), true, null,
              FieldConstraints.NONE, null, null, null, 0, false, false, false, false, true, false);
      assertThat(retired.effectivelyRequired()).isFalse();
      // And its values still validate, which is the half REQ-CORE-026 is about.
      assertThat(check(retired, "{\"old\":\"kept\"}", List.of())).isEmpty();
    }

    @Test
    @DisplayName("a secret is never projected, whatever its flags say")
    void secretsAreNotProjected() {
      FieldDefinitionView licence =
          new FieldDefinitionView(
              UUID.randomUUID(), "licenceKey", FieldDataType.SECRET, Map.of(), Map.of(), false, null,
              FieldConstraints.NONE, null, null, null, 0, true, true, true, true, false, false);
      assertThat(licence.projected()).isFalse();
      assertThat(FieldDataType.SECRET.projectable()).isFalse();
    }

    @Test
    @DisplayName("every declared kind has a token, and every token a kind")
    void tokensRoundTrip() {
      assertThat(FieldDataType.values()).hasSize(16);
      for (FieldDataType type : FieldDataType.values()) {
        assertThat(FieldDataType.ofToken(type.token())).isEqualTo(type);
      }
      assertThat(FieldDataType.ofToken("multi-enum")).isEqualTo(FieldDataType.MULTI_ENUM);
    }

    @Test
    @DisplayName("a visibility operator that needs no value says so")
    void visibilityOperators() {
      assertThat(VisibilityRule.Operator.ofToken("equals").needsValue()).isTrue();
      assertThat(VisibilityRule.Operator.ofToken("present").needsValue()).isFalse();
    }
  }
}
