/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.homeinv.plugins.api.PluginRegistry;
import de.greluc.homeinv.plugins.application.DefaultPluginRegistry;
import de.greluc.homeinv.plugins.domain.ManifestSignature;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * What a manifest's signature does to a registration (REQ-PLG-004, ADR-0085).
 *
 * <h2>Why this needs a database</h2>
 *
 * <p>{@link ManifestSignatureTest} covers the arithmetic — whether a signature is over a document.
 * What is tested here is the consequence, and the consequence lives in an upsert: a registration
 * happens again on every start-up, and the interesting question is what a second one does to the
 * state the first one left behind.
 *
 * <p>That rule has two halves pulling opposite ways. An operator's immediate measure must survive a
 * restart (REQ-SEC-082), so a re-registration may not simply re-enable what they switched off. A
 * manifest whose signature stopped verifying must be taken out of service at the next start, so a
 * re-registration may not simply leave the state alone either. The two are told apart by whether a
 * reason was recorded, and getting that wrong in either direction is silent: one way an operator's
 * switch quietly flips back, the other way a tampered manifest keeps running.
 */
@DisplayName("A plugin's registration, by what its signature says")
class ManifestSignatureIT extends AbstractIntegrationTest {

  /**
   * A different id per test, because nothing truncates the registry between them.
   *
   * <p>Every case here is about what a SECOND registration does to what a first one left behind, so
   * a shared id would make each test's starting state depend on which ran before it — and the
   * registry's upsert is exactly the thing under test. One id per method costs a constant and buys
   * independence.
   */
  private static final String VERIFIED_PLUGIN = "de.greluc.homeinv.plugin.sig-verified";

  private static final String INVALID_PLUGIN = "de.greluc.homeinv.plugin.sig-invalid";

  private static final String INVALID_PERMISSIVE_PLUGIN = "de.greluc.homeinv.plugin.sig-invalid-permissive";

  private static final String UNSIGNED_REFUSED_PLUGIN = "de.greluc.homeinv.plugin.sig-unsigned-refused";

  private static final String UNSIGNED_PERMITTED_PLUGIN = "de.greluc.homeinv.plugin.sig-unsigned-permitted";

  private static final String BROKE_LATER_PLUGIN = "de.greluc.homeinv.plugin.sig-broke-later";

  private static final String OPERATOR_OFF_PLUGIN = "de.greluc.homeinv.plugin.sig-operator-off";

  private static final String REPAIRED_PLUGIN = "de.greluc.homeinv.plugin.sig-repaired";

  private static final ManifestSignature.Result VERIFIED =
      new ManifestSignature.Result(ManifestSignature.State.VERIFIED, "");

  private static final ManifestSignature.Result UNSIGNED =
      new ManifestSignature.Result(ManifestSignature.State.UNSIGNED, "nobody signed it");

  private static final ManifestSignature.Result INVALID =
      new ManifestSignature.Result(
          ManifestSignature.State.INVALID, "its signature is not over this manifest");

  @Autowired private DefaultPluginRegistry registry;

  @Test
  @DisplayName("is enabled and marked signed when the signature verified")
  void aVerifiedManifestRuns() {
    registry.register(manifest(VERIFIED_PLUGIN, "1.0.0"), "signed:9000", null, VERIFIED, false);

    PluginRegistry.Registration stored = registry.registration(VERIFIED_PLUGIN);
    assertThat(stored.signed()).isTrue();
    assertThat(stored.disabled()).isFalse();
    assertThat(stored.stateReason()).as("nothing to say about a check that passed").isNull();
  }

  @Test
  @DisplayName("is disabled with a reason when the signature did not verify")
  void anInvalidSignatureDisablesIt() {
    registry.register(manifest(INVALID_PLUGIN, "1.0.0"), "signed:9000", null, INVALID, false);

    PluginRegistry.Registration stored = registry.registration(INVALID_PLUGIN);
    assertThat(stored.signed()).isFalse();
    assertThat(stored.disabled()).as("an altered manifest is never called").isTrue();
    assertThat(stored.stateReason())
        .contains("did not verify")
        .contains("No setting runs a plugin in this state");
  }

  @Test
  @DisplayName("stays disabled even where the operator permits unsigned plugins")
  void permittingUnsignedDoesNotPermitInvalid() {
    registry.register(manifest(INVALID_PERMISSIVE_PLUGIN, "1.0.0"), "signed:9000", null, INVALID, true);

    assertThat(registry.registration(INVALID_PERMISSIVE_PLUGIN).disabled())
        .as("an invalid signature is not an unsigned plugin, and the setting is about the other one")
        .isTrue();
  }

  @Test
  @DisplayName("is disabled when it is unsigned and this deployment does not permit that")
  void anUnsignedPluginIsRefusedByDefault() {
    registry.register(manifest(UNSIGNED_REFUSED_PLUGIN, "1.0.0"), "signed:9000", null, UNSIGNED, false);

    PluginRegistry.Registration stored = registry.registration(UNSIGNED_REFUSED_PLUGIN);
    assertThat(stored.disabled()).isTrue();
    assertThat(stored.stateReason())
        .as("the operator is told the one setting that would run it")
        .contains("HOMEINV_PLUGINS_ALLOW_UNSIGNED");
  }

  @Test
  @DisplayName("runs unsigned where the operator said so, and says so permanently")
  void anUnsignedPluginRunsWhenPermitted() {
    registry.register(manifest(UNSIGNED_PERMITTED_PLUGIN, "1.0.0"), "signed:9000", null, UNSIGNED, true);

    PluginRegistry.Registration stored = registry.registration(UNSIGNED_PERMITTED_PLUGIN);
    assertThat(stored.signed()).isFalse();
    assertThat(stored.disabled()).isFalse();
    assertThat(stored.stateReason())
        .as("the permanent warning of 09 §9.3, kept beside the registration")
        .contains("permits unsigned plugins");
  }

  @Test
  @DisplayName("goes out of service at the next start-up when its signature stopped verifying")
  void aSignatureThatBreaksLaterDisablesIt() {
    registry.register(manifest(BROKE_LATER_PLUGIN, "1.0.0"), "signed:9000", null, VERIFIED, false);
    assertThat(registry.registration(BROKE_LATER_PLUGIN).disabled()).isFalse();

    // The same plugin, a changed document, and the signature that came with the
    // old one. This is the restart after somebody edited a manifest in place.
    registry.register(manifest(BROKE_LATER_PLUGIN, "1.0.1"), "signed:9000", null, INVALID, false);

    PluginRegistry.Registration stored = registry.registration(BROKE_LATER_PLUGIN);
    assertThat(stored.disabled()).as("an upsert that left the state alone would have kept it running").isTrue();
    assertThat(stored.signed()).isFalse();
  }

  @Test
  @DisplayName("keeps the operator's switch off when it registers again")
  void anOperatorsDisableSurvivesARestart() {
    registry.register(manifest(OPERATOR_OFF_PLUGIN, "1.0.0"), "signed:9000", null, VERIFIED, false);
    registry.setDisabled(OPERATOR_OFF_PLUGIN, true, UUID.randomUUID());

    // A restart. The signature is fine, and that is not a reason to undo what an
    // operator decided (REQ-SEC-082).
    registry.register(manifest(OPERATOR_OFF_PLUGIN, "1.0.1"), "signed:9000", null, VERIFIED, false);

    PluginRegistry.Registration stored = registry.registration(OPERATOR_OFF_PLUGIN);
    assertThat(stored.disabled()).as("the immediate measure outlives the restart").isTrue();
    assertThat(stored.stateReason()).as("an operator is a decision, not a sentence").isNull();
    assertThat(stored.version()).as("and it did re-register: the version moved on").isEqualTo("1.0.1");
  }

  @Test
  @DisplayName("comes back by itself once the signature verifies again")
  void aRepairedSignatureRunsAgain() {
    registry.register(manifest(REPAIRED_PLUGIN, "1.0.0"), "signed:9000", null, INVALID, false);
    assertThat(registry.registration(REPAIRED_PLUGIN).disabled()).isTrue();

    // The manifest was put back, or re-signed. Nobody should have to remember to
    // re-enable a plugin that was only ever switched off by a broken signature.
    registry.register(manifest(REPAIRED_PLUGIN, "1.0.0"), "signed:9000", null, VERIFIED, false);

    PluginRegistry.Registration stored = registry.registration(REPAIRED_PLUGIN);
    assertThat(stored.disabled()).isFalse();
    assertThat(stored.stateReason()).isNull();
  }

  /**
   * A manifest for one of the plugins these tests register.
   *
   * @param pluginId the id, which is this method's own so that no test inherits another's state
   * @param version the plugin's own version, which is what tells two registrations apart
   * @return the document
   */
  private static byte[] manifest(String pluginId, String version) {
    return """
        apiVersion: home-inv.plugin/v1
        metadata:
          id: %s
          name: "A signed plugin"
          version: "%s"
          vendor: "greluc"
          license: "Apache-2.0"
        spec:
          contract: ">=1.0.0 <2.0.0"
          runtime: out-of-process
          implements:
            - port: MetadataResolver
              schemes: [ISBN13]
              priority: 100
          capabilities:
            - id: core:item:read
              reason: "Because the test says so"
        """
        .formatted(pluginId, version)
        .getBytes(StandardCharsets.UTF_8);
  }
}
