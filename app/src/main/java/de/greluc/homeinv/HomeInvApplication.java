/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulith;

/**
 * Entry point of the Home Inventory application.
 *
 * <p>This class is deliberately the only type directly under {@code de.greluc.homeinv}. Everything
 * else belongs to exactly one building block below it — {@code de.greluc.homeinv.inventory},
 * {@code de.greluc.homeinv.media} and so on — because Spring Modulith treats every direct
 * sub-package of this class's package as a module boundary. A class placed here by accident
 * therefore does not merely sit in the wrong folder: it becomes visible to every block at once and
 * silently removes the boundary that {@code ModularityTest} exists to prove.
 *
 * <p>The {@link Modulith} annotation lists the blocks explicitly rather than letting them be
 * discovered. Discovery would make an accidentally created package a legitimate module, which is
 * the failure this project spends an ArchUnit suite preventing (ADR-0002, REQ-NFR-019…024).
 *
 * <p>Stage 0 ships a subset of the eighteen blocks. The remainder arrive with the stages that need
 * them, and are absent here rather than present and empty — an empty module passes every boundary
 * check and teaches nothing.
 */
@Modulith(
    systemName = "Home Inventory",
    sharedModules = "platform",
    additionalPackages = {})
@SpringBootApplication
public class HomeInvApplication {

  /**
   * Starts the application.
   *
   * <p>No configuration happens here. A missing secret must abort startup with a clear message
   * rather than fall back to a generated default (CLAUDE.md, Security); that check lives in the
   * configuration validation of the {@code platform} block, where it runs during context refresh
   * and therefore also in tests.
   *
   * @param args the command line arguments handed to Spring Boot unchanged
   */
  public static void main(String[] args) {
    SpringApplication.run(HomeInvApplication.class, args);
  }
}
