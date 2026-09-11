/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rules that are not style preferences ({@code CLAUDE.md}, "The rules CI enforces here").
 *
 * <p>Spring Modulith proves the boundaries <em>between</em> blocks. These prove the rules
 * <em>inside</em> one, which Modulith has no opinion about — and they are the ones that decay
 * quietly, because breaking them never fails at runtime. A domain class that imports Spring works
 * perfectly until somebody wants to test it without a container, or extract the block.
 */
@DisplayName("The architecture rules")
class ArchitectureRulesTest {

  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("de.greluc.homeinv");

  @Test
  @DisplayName("keep the domain free of the framework")
  void domainHasNoFrameworkDependency() {
    // REQ-NFR-022. The aggregates are the part worth keeping portable and the part
    // worth testing without a context; both stop being true the moment a domain
    // class needs Spring to be constructed.
    //
    // JPA is the deliberate exception: the aggregates are mapped with jakarta.persistence,
    // which is a specification rather than a framework and travels with the entity.
    noClasses()
        .that()
        .resideInAPackage("..domain..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("org.springframework..", "..application..", "..infrastructure..")
        .because(
            "domain code must be constructible and testable without a framework, and must not "
                + "depend on the layers that depend on it (REQ-NFR-022)")
        .check(CLASSES);
  }

  @Test
  @DisplayName("keep entities inside their block")
  void entitiesDoNotLeave() {
    // REQ-NFR-023. An entity handed outward carries its persistence context with
    // it: the holder can modify the aggregate without passing the use case that
    // guards its invariants, and the call site looks exactly like one returning
    // plain data.
    methods()
        .that()
        .areDeclaredInClassesThat()
        .resideInAPackage("..api..")
        .should()
        .notHaveRawReturnType(
            com.tngtech.archunit.base.DescribedPredicate.describe(
                "an entity",
                javaClass ->
                    javaClass.isAnnotatedWith(jakarta.persistence.Entity.class)))
        .because("only *View types cross a block boundary, never an entity (REQ-NFR-023)")
        .check(CLASSES);
  }

  @Test
  @DisplayName("forbid field injection")
  void noFieldInjection() {
    // A field-injected collaborator cannot be supplied by a constructor, so the
    // class cannot be instantiated in a test without reflection - and a missing
    // dependency surfaces as a NullPointerException at first use rather than as a
    // failure to start.
    fields()
        .should()
        .notBeAnnotatedWith(org.springframework.beans.factory.annotation.Autowired.class)
        .because("constructor injection only (CLAUDE.md, Conventions)")
        .check(CLASSES);
  }

  @Test
  @DisplayName("keep SQL out of the domain and the application layer")
  void sqlLivesInInfrastructure() {
    // Not purity: a query in a use case is a query that cannot be swapped when the
    // store changes, and one that nobody looks for when tuning. ADR-0017 puts
    // hand-written SQL in infrastructure, next to the repository it belongs to.
    noClasses()
        .that()
        .resideInAnyPackage("..domain..", "..application..")
        .should()
        .dependOnClassesThat()
        .haveFullyQualifiedName("org.springframework.jdbc.core.simple.JdbcClient")
        .because("hand-written SQL belongs in infrastructure (ADR-0017)")
        .check(CLASSES);
  }

  @Test
  @DisplayName("let no controller reach past a published interface")
  void controllersUseOnlyPublishedTypes() {
    // The access layer decides nothing (REQ-SEC-022) and must therefore also know
    // nothing: a controller that can see a repository is a controller that will
    // eventually use one, and the authorization decision moves with it.
    noClasses()
        .that()
        .resideInAPackage("de.greluc.homeinv.rest..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("..infrastructure..", "..domain..")
        .because(
            "an access adapter translates and decides nothing; reaching a repository is how it "
                + "starts deciding (REQ-SEC-022, ADR-0010)")
        .check(CLASSES);
  }
}
