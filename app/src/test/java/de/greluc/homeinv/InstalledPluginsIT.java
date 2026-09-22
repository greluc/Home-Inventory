/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import de.greluc.homeinv.plugins.api.PluginRegistry;
import de.greluc.homeinv.plugins.infrastructure.InstalledPlugins;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Registering what an operator installed (REQ-PLG-008, REQ-PLG-013).
 *
 * <p>The property that matters most is the one that is easiest to lose: <b>a foreign plugin can
 * never prevent the core from starting</b> (09 §9.11). Every way a plugin's own files can be wrong
 * is therefore a skipped entry and a log line, never an exception out of startup — and the entries
 * beside it are still registered, because one bad plugin is not a reason to have none.
 */
@DisplayName("The installed plugins")
class InstalledPluginsIT extends AbstractIntegrationTest {

  @Autowired private InstalledPlugins installed;
  @Autowired private PluginRegistry registry;

  @Test
  @DisplayName("are registered from the list the operator's deployment generated")
  void registersWhatIsListed(@TempDir Path directory) throws Exception {
    Path manifest = directory.resolve("isbn.yaml");
    Files.writeString(manifest, manifestOf("de.greluc.homeinv.plugin.isbn", ">=1.0.0 <2.0.0"));
    Path list = list(directory, entry("de.greluc.homeinv.plugin.isbn", manifest));

    run(list);

    assertThat(registry.installed(200))
        .extracting(PluginRegistry.Registration::pluginId)
        .contains("de.greluc.homeinv.plugin.isbn");
  }

  @Test
  @DisplayName("skip a plugin whose contract this core does not serve, and carry on")
  void anIncompatibleContract(@TempDir Path directory) throws Exception {
    Path good = directory.resolve("good.yaml");
    Files.writeString(good, manifestOf("de.greluc.homeinv.plugin.good", ">=1.0.0 <2.0.0"));
    Path future = directory.resolve("future.yaml");
    Files.writeString(future, manifestOf("de.greluc.homeinv.plugin.future", ">=9.0.0 <10.0.0"));

    Path list =
        list(
            directory,
            entry("de.greluc.homeinv.plugin.future", future),
            entry("de.greluc.homeinv.plugin.good", good));

    // No exception, and the other one is still registered: one plugin built for
    // another contract is not a reason to have none (09 §9.11).
    assertThatCode(() -> run(list)).doesNotThrowAnyException();

    assertThat(registry.installed(200)).extracting(PluginRegistry.Registration::pluginId)
        .contains("de.greluc.homeinv.plugin.good")
        .doesNotContain("de.greluc.homeinv.plugin.future");
  }

  @Test
  @DisplayName("skip a plugin whose manifest disagrees with the list about who it is")
  void aDisagreementAboutIdentity(@TempDir Path directory) throws Exception {
    Path manifest = directory.resolve("claims.yaml");
    Files.writeString(manifest, manifestOf("de.greluc.homeinv.plugin.actual", ">=1.0.0 <2.0.0"));

    // A grant is recorded against the id, so taking the wrong one would attach
    // somebody's consent to the wrong plugin.
    Path list = list(directory, entry("de.greluc.homeinv.plugin.claimed", manifest));
    run(list);

    assertThat(registry.installed(200)).extracting(PluginRegistry.Registration::pluginId)
        .doesNotContain("de.greluc.homeinv.plugin.claimed", "de.greluc.homeinv.plugin.actual");
  }

  @Test
  @DisplayName("survive a manifest that is not one, a file that is missing, and a list that is not there")
  void nothingHereStopsTheCore(@TempDir Path directory) throws Exception {
    Path rubbish = directory.resolve("rubbish.yaml");
    Files.writeString(rubbish, "this: is not a manifest\n");

    Path list =
        list(
            directory,
            entry("de.greluc.homeinv.plugin.rubbish", rubbish),
            entry("de.greluc.homeinv.plugin.missing", directory.resolve("nowhere.yaml")));

    assertThatCode(() -> run(list)).doesNotThrowAnyException();
    assertThatCode(() -> run(directory.resolve("no-such-list.yaml"))).doesNotThrowAnyException();
    // And an instance with no list at all, which is what `minimal` is.
    assertThatCode(() -> run(null)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("are registered from a list that carries the manifest itself")
  void registersWhatIsInlined(@TempDir Path directory) throws Exception {
    // What `deploy/generate.py` writes: one file for the deployment, with each
    // manifest in it, rather than one file per plugin plus a path in each entry
    // — a path is a thing that can be right in the list and wrong in the mount.
    Path list =
        list(
            directory,
            inlineEntry(
                "de.greluc.homeinv.plugin.inline",
                manifestOf("de.greluc.homeinv.plugin.inline", ">=1.0.0 <2.0.0")));

    run(list);

    assertThat(registry.installed(200))
        .extracting(PluginRegistry.Registration::pluginId)
        .contains("de.greluc.homeinv.plugin.inline");
  }

  @Test
  @DisplayName("refuse an entry that carries both a path and a document, or neither")
  void exactlyOneManifest(@TempDir Path directory) throws Exception {
    // A reader that preferred one would silently ignore the other, and an
    // operator who changed the file would see nothing change.
    Path both =
        list(
            directory,
            """
                 - id: de.greluc.homeinv.plugin.both
                   manifest: "/somewhere/manifest.yaml"
                   manifestInline: "apiVersion: home-inv.plugin/v1"
               """);
    assertThatCode(() -> run(both)).doesNotThrowAnyException();
    assertThat(registry.installed(200))
        .extracting(PluginRegistry.Registration::pluginId)
        .doesNotContain("de.greluc.homeinv.plugin.both");

    Path neither = list(directory, "   - id: de.greluc.homeinv.plugin.neither\n");
    assertThatCode(() -> run(neither)).doesNotThrowAnyException();
    assertThat(registry.installed(200))
        .extracting(PluginRegistry.Registration::pluginId)
        .doesNotContain("de.greluc.homeinv.plugin.neither");
  }

  // -------------------------------------------------------------------------

  /**
   * Points the reader at one list and runs it.
   *
   * <p>The field rather than a property, because the alternative is a Spring context per case and
   * the thing under test is what the reader does with a file.
   *
   * @param list the list to read, or {@code null} for none
   */
  private void run(Path list) {
    ReflectionTestUtils.setField(installed, "listFile", list == null ? "" : list.toString());
    installed.registerInstalled(null);
  }

  private static Path list(Path directory, String... entries) throws Exception {
    Path list = directory.resolve("plugins-" + entries.length + "-" + entries[0].hashCode() + ".yaml");
    Files.writeString(list, "plugins:\n" + String.join("", entries), StandardCharsets.UTF_8);
    return list;
  }

  private static String entry(String id, Path manifest) {
    return """
             - id: %s
               manifest: "%s"
               endpoint: "%s:9000"
               signed: true
           """
        .formatted(id, manifest.toString().replace("\\", "\\\\"), id);
  }

  /**
   * An entry carrying the manifest itself, indented as a YAML block scalar.
   *
   * @param id the plugin
   * @param manifest the document
   * @return the entry
   */
  private static String inlineEntry(String id, String manifest) {
    String indented =
        manifest
            .lines()
            .map(line -> "       " + line)
            .collect(java.util.stream.Collectors.joining("\n"));
    return """
             - id: %s
               endpoint: "%s:9000"
               signed: true
               manifestInline: |
           %s
           """
        .formatted(id, id, indented);
  }

  private static String manifestOf(String id, String contract) {
    return """
        apiVersion: home-inv.plugin/v1
        metadata:
          id: %s
          name: "A plugin"
          version: "1.0.0"
          vendor: "greluc"
          license: "Apache-2.0"
        spec:
          contract: "%s"
          runtime: out-of-process
          implements:
            - port: MetadataResolver
              schemes: [ISBN13]
              priority: 100
          capabilities:
            - id: core:item:read
              reason: "Because the test says so"
        """
        .formatted(id, contract);
  }
}
