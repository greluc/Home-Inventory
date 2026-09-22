/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.homeinv.catalog.api.FieldConstraints;
import de.greluc.homeinv.catalog.api.FieldDataType;
import de.greluc.homeinv.catalog.api.FieldDefinitionView;
import de.greluc.homeinv.catalog.domain.FieldTightening;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A child type may tighten an inherited field and may never widen it (REQ-CORE-013).
 *
 * <h2>Why every branch, one at a time</h2>
 *
 * <p>This is the rule that decides whether one tenant's type hierarchy can be used to weaken a
 * constraint their own parent type declared — drop a maximum, lower a minimum, swap the value list
 * — and it answers with the <b>name of the first property widened</b>, which is what an
 * administrator reads when a publish is refused. A rule that returned the wrong name would be a
 * refusal nobody could act on, and a rule that missed a case would be no rule at all.
 *
 * <p>Eight of its returns had no test before 2026-09-21, which is what
 * {@code jacocoTestCoverageVerification} now catches (REQ-NFR-025): the domain package is where a
 * gap costs the most and is also where a test is cheapest, because there is nothing to start.
 *
 * <p>No container, no Spring: this class touches neither.
 */
@DisplayName("Tightening an inherited field")
class FieldTighteningTest {

  @Test
  @DisplayName("allows a child that changes nothing")
  void anIdenticalFieldWidensNothing() {
    FieldDefinitionView parent = field(FieldDataType.TEXT, false, null, FieldConstraints.NONE);

    assertThat(FieldTightening.widens(parent, parent)).isEmpty();
  }

  @Test
  @DisplayName("refuses a different data type")
  void theDataTypeMayNotChange() {
    assertThat(
            FieldTightening.widens(
                field(FieldDataType.TEXT, false, null, FieldConstraints.NONE),
                field(FieldDataType.DECIMAL, false, null, FieldConstraints.NONE)))
        .contains("the data type");
  }

  @Test
  @DisplayName("refuses making a required field optional")
  void requiredMayNotBeDropped() {
    assertThat(
            FieldTightening.widens(
                field(FieldDataType.TEXT, false, null, FieldConstraints.NONE),
                field(FieldDataType.TEXT, true, null, FieldConstraints.NONE)))
        .contains("required");
  }

  @Test
  @DisplayName("refuses a different value list, in either direction")
  void theValueListMayNotChange() {
    UUID list = UUID.randomUUID();

    // Nothing here can tell whether a different list is a subset of the first,
    // so both directions are refused rather than one of them guessed at.
    assertThat(
            FieldTightening.widens(
                field(FieldDataType.TEXT, false, UUID.randomUUID(), FieldConstraints.NONE),
                field(FieldDataType.TEXT, false, list, FieldConstraints.NONE)))
        .contains("the value list");
    assertThat(
            FieldTightening.widens(
                field(FieldDataType.TEXT, false, null, FieldConstraints.NONE),
                field(FieldDataType.TEXT, false, list, FieldConstraints.NONE)))
        .contains("the value list");
  }

  @Test
  @DisplayName("refuses dropping or lowering a minimum")
  void theMinimumMayOnlyRise() {
    FieldDefinitionView parent = number(constraints().min(BigDecimal.TEN).build());

    assertThat(FieldTightening.widens(number(FieldConstraints.NONE), parent)).contains("min");
    assertThat(FieldTightening.widens(number(constraints().min(BigDecimal.ONE).build()), parent))
        .contains("min");
    assertThat(
            FieldTightening.widens(
                number(constraints().min(new BigDecimal("11")).build()), parent))
        .as("raising it is tightening, which is the whole point of overriding a field")
        .isEmpty();
  }

  @Test
  @DisplayName("refuses dropping or raising a maximum")
  void theMaximumMayOnlyFall() {
    FieldDefinitionView parent = number(constraints().max(BigDecimal.TEN).build());

    assertThat(FieldTightening.widens(number(FieldConstraints.NONE), parent)).contains("max");
    assertThat(
            FieldTightening.widens(
                number(constraints().max(new BigDecimal("11")).build()), parent))
        .contains("max");
    assertThat(FieldTightening.widens(number(constraints().max(BigDecimal.ONE).build()), parent))
        .isEmpty();
  }

  @Test
  @DisplayName("refuses dropping or lowering a minimum length")
  void theMinimumLengthMayOnlyRise() {
    FieldDefinitionView parent = text(constraints().minLength(5).build());

    assertThat(FieldTightening.widens(text(FieldConstraints.NONE), parent)).contains("minLength");
    assertThat(FieldTightening.widens(text(constraints().minLength(4).build()), parent))
        .contains("minLength");
    assertThat(FieldTightening.widens(text(constraints().minLength(6).build()), parent)).isEmpty();
  }

  @Test
  @DisplayName("refuses dropping or raising a maximum length")
  void theMaximumLengthMayOnlyFall() {
    FieldDefinitionView parent = text(constraints().maxLength(5).build());

    assertThat(FieldTightening.widens(text(FieldConstraints.NONE), parent)).contains("maxLength");
    assertThat(FieldTightening.widens(text(constraints().maxLength(6).build()), parent))
        .contains("maxLength");
    assertThat(FieldTightening.widens(text(constraints().maxLength(4).build()), parent)).isEmpty();
  }

  @Test
  @DisplayName("refuses any change to a declared pattern")
  void thePatternMayNotChange() {
    FieldDefinitionView parent = text(constraints().pattern("[A-Z]{3}").build());

    // Not "a narrower pattern is fine": deciding whether one regular expression
    // accepts a subset of another is not something this rule can do, so a
    // different pattern is refused and an added one is allowed.
    assertThat(FieldTightening.widens(text(FieldConstraints.NONE), parent)).contains("the pattern");
    assertThat(FieldTightening.widens(text(constraints().pattern("[A-Z]+").build()), parent))
        .contains("the pattern");
    assertThat(
            FieldTightening.widens(
                text(constraints().pattern("[A-Z]{3}").build()), text(FieldConstraints.NONE)))
        .as("a child may add a pattern where the parent declared none")
        .isEmpty();
  }

  @Test
  @DisplayName("refuses removing or changing a declared unit")
  void theUnitMayNotChange() {
    FieldDefinitionView parent = number(constraints().unit("EUR").build());

    // A field that accepted only euros must not start accepting dollars.
    assertThat(FieldTightening.widens(number(FieldConstraints.NONE), parent)).contains("the unit");
    assertThat(FieldTightening.widens(number(constraints().unit("USD").build()), parent))
        .contains("the unit");
    assertThat(
            FieldTightening.widens(
                number(constraints().unit("EUR").build()), number(FieldConstraints.NONE)))
        .as("a child may add a unit where the parent declared none")
        .isEmpty();
  }

  @Test
  @DisplayName("treats an absent constraints object as no constraints at all")
  void nullConstraintsAreNone() {
    assertThat(FieldTightening.widens(text(null), text(null))).isEmpty();
    assertThat(FieldTightening.widens(text(null), text(constraints().maxLength(5).build())))
        .contains("maxLength");
  }

  /** A builder for the seven-component constraints record, so a test names only what it means. */
  private static Constraints constraints() {
    return new Constraints();
  }

  /** Collects the few constraint values a test sets and leaves the rest null. */
  private static final class Constraints {
    private String pattern;
    private BigDecimal min;
    private BigDecimal max;
    private Integer minLength;
    private Integer maxLength;
    private String unit;

    private Constraints pattern(String value) {
      this.pattern = value;
      return this;
    }

    private Constraints min(BigDecimal value) {
      this.min = value;
      return this;
    }

    private Constraints max(BigDecimal value) {
      this.max = value;
      return this;
    }

    private Constraints minLength(Integer value) {
      this.minLength = value;
      return this;
    }

    private Constraints maxLength(Integer value) {
      this.maxLength = value;
      return this;
    }

    private Constraints unit(String value) {
      this.unit = value;
      return this;
    }

    private FieldConstraints build() {
      return new FieldConstraints(pattern, min, max, minLength, maxLength, unit, null);
    }
  }

  /**
   * A text field carrying the given constraints.
   *
   * @param constraints what it limits, or null for an absent constraints object
   * @return the definition
   */
  private static FieldDefinitionView text(FieldConstraints constraints) {
    return field(FieldDataType.TEXT, false, null, constraints);
  }

  /**
   * A decimal field carrying the given constraints.
   *
   * @param constraints what it limits, or null for an absent constraints object
   * @return the definition
   */
  private static FieldDefinitionView number(FieldConstraints constraints) {
    return field(FieldDataType.DECIMAL, false, null, constraints);
  }

  /**
   * One field definition, with everything the rule does not read left at a neutral value.
   *
   * @param dataType the type
   * @param required whether a value is demanded
   * @param valueListId the list it draws from, or null
   * @param constraints what it limits, or null
   * @return the definition
   */
  private static FieldDefinitionView field(
      FieldDataType dataType, boolean required, UUID valueListId, FieldConstraints constraints) {
    return new FieldDefinitionView(
        UUID.randomUUID(),
        "field",
        dataType,
        Map.of("en", "Field"),
        Map.of(),
        required,
        null,
        constraints,
        valueListId,
        null, // a visibility rule; the tightening rule never reads one
        null,
        0,
        false,
        false,
        false,
        false,
        false,
        false);
  }
}
