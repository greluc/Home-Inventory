/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: Apache-2.0
 */
package de.greluc.homeinv.plugin.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every manifest this project ships is one this core would accept.
 *
 * <p>The five first-party plugins live in {@code plugins/} ([ADR-0072]) and are installed exactly
 * like a third party's: an operator mounts a manifest and the core reads it at registration. So the
 * reference manifest each one ships has to pass the same reader — and until 2026-09-20 nothing
 * checked that, which is the shape of the defect this test exists for.
 *
 * <p>That defect was real and cost months: {@code PasswordBreachCheck} was added as the fifteenth
 * port and never added to the reader, so a manifest declaring it was refused at registration with a
 * message listing the ports it was not among. A plugin nobody could install, and nothing said why.
 * {@code PortCatalogueTest} closed that from the catalogue's side; this closes it from the
 * plugins' side, for every manifest that arrives later without anybody adding a test.
 */
@DisplayName("The first-party manifests")
class FirstPartyManifestsTest {

  private static final Path PLUGINS = Path.of("..", "plugins");

  @Test
  @DisplayName("are readable, and declare only ports and capabilities this core has")
  void everyShippedManifestIsAcceptable() {
    List<Path> manifests = shipped();

    // Zero is a passing test that examined nothing. The directory exists from
    // the day ADR-0072 was written, and a run that finds it empty is a run
    // against a tree where the plugins were moved rather than one where they
    // are fine.
    assertThat(manifests)
        .as(
            "plugins/*/manifest.yaml is where a first-party plugin's reference manifest lives "
                + "(ADR-0072). None found, so this test asserted nothing.")
        .isNotEmpty();

    for (Path manifest : manifests) {
      byte[] document = read(manifest);
      PluginManifest parsed = PluginManifestReader.read(document);

      assertThat(parsed.metadata().id())
          .as("%s declares a reverse-domain id", manifest)
          .startsWith("de.greluc.homeinv.plugin.");
      assertThat(parsed.metadata().license())
          .as(
              "%s is AGPL like the rest of the first-party code. The permissive licence is on the "
                  + "contract a third party compiles against, not on our implementations of it "
                  + "(ADR-0072, ADR-0018)",
              manifest)
          .isEqualTo("AGPL-3.0-or-later");
      assertThat(parsed.spec().implementsPorts())
          .as("%s implements at least one port", manifest)
          .isNotEmpty();
      assertThat(parsed.spec().capabilities())
          .as(
              "%s asks for at least one capability. A plugin that asks for nothing leaves an "
                  + "administrator nothing to agree to, and without a grant nothing is possible "
                  + "(REQ-PLG-005)",
              manifest)
          .isNotEmpty();
      assertThat(ContractVersion.covers(parsed.spec().contract()))
          .as(
              "%s supports the contract this core serves (%s); one that does not is a plugin that "
                  + "registers nowhere",
              manifest, ContractVersion.SERVED)
          .isTrue();
    }
  }

  /**
   * Every reference manifest under {@code plugins/}.
   *
   * @return the paths, sorted so a failure names them in a stable order
   */
  private static List<Path> shipped() {
    if (!Files.isDirectory(PLUGINS)) {
      return List.of();
    }
    List<Path> found = new ArrayList<>();
    try (Stream<Path> entries = Files.list(PLUGINS)) {
      entries
          .filter(Files::isDirectory)
          .map(directory -> directory.resolve("manifest.yaml"))
          .filter(Files::isRegularFile)
          .sorted()
          .forEach(found::add);
    } catch (IOException unreadable) {
      throw new UncheckedIOException("plugins/ could not be listed", unreadable);
    }
    return found;
  }

  private static byte[] read(Path manifest) {
    try {
      return Files.readAllBytes(manifest);
    } catch (IOException unreadable) {
      throw new UncheckedIOException(manifest + " could not be read", unreadable);
    }
  }
}
