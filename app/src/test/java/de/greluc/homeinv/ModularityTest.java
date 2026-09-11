/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

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

  private final ApplicationModules modules = ApplicationModules.of(HomeInvApplication.class);

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
