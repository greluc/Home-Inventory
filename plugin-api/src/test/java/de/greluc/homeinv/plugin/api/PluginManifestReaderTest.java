/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: Apache-2.0
 */
package de.greluc.homeinv.plugin.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reading a plugin manifest, and refusing one that cannot mean anything (REQ-PLG-004).
 *
 * <p>The refusals are most of this test, because they are most of the value. A manifest is a
 * stranger's document that decides what a plugin may do, so every way of getting it wrong should
 * end in a sentence its author can act on rather than in a plugin that quietly asks for less than
 * it meant to.
 *
 * <p>The document in {@link #theExampleFrom09} is the one 09 §9.3 prints. If the chapter and this
 * test disagree, one of them is wrong and somebody has to say which — which is the point of
 * parsing the published example rather than a convenient one.
 */
@DisplayName("A plugin manifest")
class PluginManifestReaderTest {

  /** 09 §9.3's own example, as the chapter prints it. */
  private static final String EXAMPLE =
      """
      apiVersion: home-inv.plugin/v1
      metadata:
        id: de.greluc.homeinv.plugin.isbn
        name: "ISBN metadata"
        version: "1.4.2"
        vendor: "greluc"
        license: "Apache-2.0"
        homepage: "https://github.com/greluc/homeinv-plugin-isbn"
        descriptions:
          de: "Loest ISBN-10 und ISBN-13 ueber Open Library und die DNB auf."
          en: "Resolves ISBN-10 and ISBN-13 via Open Library and DNB."
      spec:
        contract: ">=1.0.0 <2.0.0"
        runtime: out-of-process
        implements:
          - port: MetadataResolver
            schemes: [ISBN10, ISBN13]
            priority: 100
        capabilities:
          - id: network:outbound
            hosts: ["openlibrary.org", "services.dnb.de"]
            reason: "Querying the metadata sources"
          - id: core:item:read
            reason: "Read existing fields so that only gaps are proposed"
        settings:
          - key: dnbApiToken
            type: secret
            required: false
            label: { de: "DNB-Zugangstoken", en: "DNB access token" }
          - key: preferredSource
            type: enum
            values: [openlibrary, dnb]
            default: openlibrary
        health:
          endpoint: /healthz
          intervalSeconds: 60
        resources:
          memoryMiB: 128
          timeoutSeconds: 5
      """;

  @Test
  @DisplayName("reads the example 09 §9.3 publishes")
  void theExampleFrom09() {
    PluginManifest manifest = read(EXAMPLE);

    assertThat(manifest.metadata().id()).isEqualTo("de.greluc.homeinv.plugin.isbn");
    assertThat(manifest.metadata().descriptions()).containsKeys("de", "en");
    assertThat(manifest.spec().contract()).isEqualTo(">=1.0.0 <2.0.0");
    assertThat(manifest.spec().runtime()).isEqualTo(PluginManifest.Runtime.OUT_OF_PROCESS);

    assertThat(manifest.spec().implementsPorts()).singleElement().satisfies(binding -> {
      assertThat(binding.port()).isEqualTo("MetadataResolver");
      assertThat(binding.schemes()).containsExactly("ISBN10", "ISBN13");
      assertThat(binding.priority()).isEqualTo(100);
    });

    assertThat(manifest.spec().capabilities()).extracting(PluginManifest.Capability::id)
        .containsExactly("network:outbound", "core:item:read");
    // The host list is what the egress proxy's allowlist is compiled from
    // (ADR-0027), so it survives the parse exactly.
    assertThat(manifest.spec().capabilities().getFirst().hosts())
        .containsExactly("openlibrary.org", "services.dnb.de");

    assertThat(manifest.spec().settings()).hasSize(2);
    assertThat(manifest.spec().settings().get(1).values()).containsExactly("openlibrary", "dnb");
    assertThat(manifest.spec().health().intervalSeconds()).isEqualTo(60);
    assertThat(manifest.spec().resources().memoryMiB()).isEqualTo(128);
  }

  @Test
  @DisplayName("refuses a key nobody defined, rather than ignoring it")
  void aMisspeltKeyIsRefused() {
    // `capabilites`. Ignored, this is a plugin that asks for nothing, is granted
    // nothing, and fails at runtime in front of somebody who cannot fix it.
    assertThatThrownBy(() -> read(EXAMPLE.replace("  capabilities:", "  capabilites:")))
        .isInstanceOf(InvalidManifestException.class)
        .hasMessageContaining("capabilites");
  }

  @Test
  @DisplayName("refuses a capability this core does not grant")
  void anUnknownCapability() {
    assertThatThrownBy(() -> read(EXAMPLE.replace("core:item:read", "core:item:readall")))
        .isInstanceOf(InvalidManifestException.class)
        .hasMessageContaining("core:item:readall");
  }

  @Test
  @DisplayName("refuses network:outbound that names nowhere to go")
  void outboundWithoutATarget() {
    String noHosts =
        EXAMPLE.replace("      hosts: [\"openlibrary.org\", \"services.dnb.de\"]\n", "");
    assertThatThrownBy(() -> read(noHosts))
        .isInstanceOf(InvalidManifestException.class)
        // An empty allowlist is a plugin asking for the internet.
        .hasMessageContaining("network:outbound");
  }

  @Test
  @DisplayName("refuses a port this core has no extension point for")
  void anUnknownPort() {
    assertThatThrownBy(() -> read(EXAMPLE.replace("port: MetadataResolver", "port: Everything")))
        .isInstanceOf(InvalidManifestException.class)
        .hasMessageContaining("Everything");
  }

  @Test
  @DisplayName("refuses an id that is not a reverse-domain name")
  void anIdThatIsNotStable() {
    // The id is what a tenant's grant is recorded against, so it has to be
    // unmistakable: "isbn" belongs to whoever claims it first.
    assertThatThrownBy(() -> read(EXAMPLE.replace("id: de.greluc.homeinv.plugin.isbn", "id: isbn")))
        .isInstanceOf(InvalidManifestException.class)
        .hasMessageContaining("reverse-domain");
  }

  @Test
  @DisplayName("refuses a manifest format it does not read")
  void anotherApiVersion() {
    assertThatThrownBy(() -> read(EXAMPLE.replace("home-inv.plugin/v1", "home-inv.plugin/v2")))
        .isInstanceOf(InvalidManifestException.class)
        .hasMessageContaining("home-inv.plugin/v1");
  }

  @Test
  @DisplayName("refuses a plugin that implements nothing")
  void noPorts() {
    String nothing =
        EXAMPLE.replace(
            """
              implements:
                - port: MetadataResolver
                  schemes: [ISBN10, ISBN13]
                  priority: 100
            """,
            "  implements: []\n");
    assertThatThrownBy(() -> read(nothing))
        .isInstanceOf(InvalidManifestException.class)
        .hasMessageContaining("implements");
  }

  @Test
  @DisplayName("names no Java type, whatever the document asks for")
  void itIsNotACodeLoader() {
    // An unrestricted YAML loader turns this into a constructor call. A manifest
    // comes from whoever wrote the plugin, so the loader is the safe one and
    // this is the test that says so.
    String tagged =
        """
        apiVersion: home-inv.plugin/v1
        metadata: !!javax.script.ScriptEngineManager [!!java.net.URLClassLoader [[!!java.net.URL ["http://localhost/"]]]]
        spec: {}
        """;
    assertThatThrownBy(() -> read(tagged)).isInstanceOf(InvalidManifestException.class);
  }

  @Test
  @DisplayName("refuses an empty document and one that is not a mapping")
  void notAManifestAtAll() {
    assertThatThrownBy(() -> read("")).isInstanceOf(InvalidManifestException.class);
    assertThatThrownBy(() -> read("- one\n- two\n"))
        .isInstanceOf(InvalidManifestException.class)
        .hasMessageContaining("mapping");
  }

  @Test
  @DisplayName("refuses an enum setting that offers nothing to choose from")
  void anEnumWithoutValues() {
    assertThatThrownBy(() -> read(EXAMPLE.replace("      values: [openlibrary, dnb]\n", "")))
        .isInstanceOf(InvalidManifestException.class)
        .hasMessageContaining("preferredSource");
  }

  private static PluginManifest read(String yaml) {
    return PluginManifestReader.read(yaml.getBytes(StandardCharsets.UTF_8));
  }
}
