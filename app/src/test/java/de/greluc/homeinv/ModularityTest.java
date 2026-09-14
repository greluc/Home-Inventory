/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Proves the building-block boundaries hold ({@code REQ-NFR-019}…{@code 024}).
 *
 * <p>This test is the reason the architecture can be called modular at all. Without mechanical
 * verification a modular monolith decays into a large ball of mud within a year — that is risk R3,
 * the single biggest one in this design, and this is the countermeasure that has been shown to
 * work. A rule nobody checks is a rule that is already broken somewhere.
 *
 * <p>What Spring Modulith verifies here, from the package structure alone:
 *
 * <ul>
 *   <li>a block reaches another only through its published {@code api} package, so an internal type
 *       cannot become somebody else's dependency by accident;
 *   <li>there is no cycle between blocks, which is what keeps "extract this block into a service"
 *       from becoming "extract these four blocks";
 *   <li>every block named in {@code @Modulith} exists, so a typo does not silently disable a
 *       boundary.
 * </ul>
 */
@DisplayName("The building blocks")
class ModularityTest {

  /**
   * The generated protobuf contract, which is not a building block.
   *
   * <p>{@code proto/home_inv/plugin/v1/blob_store.proto} declares
   * {@code java_package = de.greluc.homeinv.plugin.v1}, and Modulith treats every direct
   * sub-package of the application's as a module — so the generated classes would become a block
   * called {@code plugin}, with no {@code api} package and therefore nothing anybody may use.
   *
   * <p>Excluding it is the right answer rather than a workaround: it is a wire contract shared with
   * the Rust service and, from stage 3, with third-party plugin authors. It has no internals to
   * protect and no dependencies to police — every one of its classes is public by definition,
   * because that is what a generated contract is.
   *
   * <p>Widened from {@code plugin.v1..} to {@code plugin..} on 2026-09-14, when
   * {@code de.greluc.homeinv.plugin.api} arrived with the manifest model. That package is a
   * <b>separate Gradle project</b> — Apache-2.0, depending on nothing in the core (ADR-0018) — and
   * therefore not a building block of this modulith at all. Its own boundary is checked by
   * {@code :plugin-api:noCoreOnTheClasspath}, which is a stricter rule than this one: the core may
   * not appear on its classpath by any route, transitive ones included.
   *
   * <p>The name still says {@code GENERATED_CONTRACT} because both halves are contracts shared with
   * plugin authors, one generated and one written.
   */
  private static final DescribedPredicate<JavaClass> GENERATED_CONTRACT =
      JavaClass.Predicates.resideInAPackage("de.greluc.homeinv.plugin..");

  private final ApplicationModules modules =
      ApplicationModules.of(HomeInvApplication.class, GENERATED_CONTRACT);

  @Test
  @DisplayName("respect their published boundaries and contain no cycle")
  void boundariesHold() {
    modules.verify();
  }

  @Test
  @DisplayName("are documented from the code rather than from a drawing")
  void documentationIsGenerated() {
    // Generated into build/, not committed: a diagram in the repository is a
    // diagram that disagrees with the code the week after it is drawn. The value
    // is that it is producible on demand and always current.
    new Documenter(modules).writeModulesAsPlantUml().writeIndividualModulesAsPlantUml();
  }
}
