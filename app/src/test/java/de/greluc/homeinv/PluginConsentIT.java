/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.plugins.application.DefaultPluginRegistry;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A tenant administrator seeing what a plugin asks for, and answering (REQ-PLG-005, 09 §9.4).
 *
 * <p>The listing shows both halves at once — what the manifest asks for and what this tenant has
 * agreed to — because "what it wants" without "what it has" is not a decision anybody can take.
 *
 * <p>There is deliberately no endpoint here that installs anything: installation is an operator's
 * act outside the running system (REQ-PLG-013), and this test asserts the shape of what a tenant
 * <i>can</i> do rather than the absence of what it cannot.
 */
@DisplayName("Consenting to a plugin")
class PluginConsentIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple-42";
  private static final String PLUGIN = "de.greluc.homeinv.plugin.consent";
  private static final String PLUGINS = "/api/v1/plugins";

  @Autowired private DefaultPluginRegistry registry;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private AppUserRepository users;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private ObjectMapper json;

  @Test
  @DisplayName("shows what it asks for and what it has, and nothing is granted to begin with")
  void bothHalves() throws Exception {
    MockHttpSession session = anAdministrator("both-halves");
    registry.register(manifest("core:item:read", "network:outbound"), "consent:9000", null, true);

    JsonNode plugin = onePlugin(session);
    assertThat(plugin.get("name").asString()).isEqualTo("A plugin that asks");
    assertThat(plugin.get("signed").asBoolean()).isTrue();

    // Everything asked for, nothing granted. That is the state a plugin starts
    // in and there is no base entitlement (09 §9.4).
    assertThat(capability(plugin, "core:item:read")).isFalse();
    assertThat(capability(plugin, "network:outbound")).isFalse();
  }

  @Test
  @DisplayName("records an agreement, and a withdrawal, and says so in the listing")
  void grantingAndWithdrawing() throws Exception {
    MockHttpSession session = anAdministrator("granting");
    registry.register(manifest("core:item:read"), "consent:9000", null, true);

    mockMvc
        .perform(
            put(PLUGINS + "/" + PLUGIN + "/capabilities/core:item:read").session(session).with(csrf()))
        .andExpect(status().isNoContent());
    assertThat(capability(onePlugin(session), "core:item:read")).isTrue();

    // Twice is once: agreeing again changes nothing and says so by succeeding.
    mockMvc
        .perform(
            put(PLUGINS + "/" + PLUGIN + "/capabilities/core:item:read").session(session).with(csrf()))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(
            delete(PLUGINS + "/" + PLUGIN + "/capabilities/core:item:read")
                .session(session)
                .with(csrf()))
        .andExpect(status().isNoContent());
    assertThat(capability(onePlugin(session), "core:item:read")).isFalse();

    // And withdrawing what is not there is not an error: what a caller wants is
    // "this plugin may not do this here", and that is already true.
    mockMvc
        .perform(
            delete(PLUGINS + "/" + PLUGIN + "/capabilities/core:item:read")
                .session(session)
                .with(csrf()))
        .andExpect(status().isNoContent());
  }

  @Test
  @DisplayName("refuses consent to something the plugin never asked for")
  void consentToSomethingUnasked() throws Exception {
    MockHttpSession session = anAdministrator("unasked");
    registry.register(manifest("core:item:read"), "consent:9000", null, true);

    // It would be waiting as a permission if the plugin asked later, which is
    // the silent escalation REQ-PLG-006 exists to prevent.
    mockMvc
        .perform(
            put(PLUGINS + "/" + PLUGIN + "/capabilities/network:outbound")
                .session(session)
                .with(csrf()))
        .andExpect(status().isUnprocessableContent());
  }

  @Test
  @DisplayName("answers 404 for a plugin nobody installed")
  void nothingInstalledUnderThatName() throws Exception {
    MockHttpSession session = anAdministrator("absent");

    mockMvc
        .perform(get(PLUGINS + "/de.greluc.homeinv.plugin.nowhere").session(session))
        .andExpect(status().isNotFound());
  }

  // -------------------------------------------------------------------------

  private JsonNode onePlugin(MockHttpSession session) throws Exception {
    String body =
        mockMvc
            .perform(get(PLUGINS + "/" + PLUGIN).session(session))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    return json.readTree(body);
  }

  private static boolean capability(JsonNode plugin, String id) {
    for (JsonNode capability : plugin.get("capabilities")) {
      if (id.equals(capability.get("id").asString())) {
        return capability.get("granted").asBoolean();
      }
    }
    throw new IllegalStateException("The plugin does not ask for " + id);
  }

  private static byte[] manifest(String... capabilities) {
    StringBuilder asked = new StringBuilder();
    for (String capability : capabilities) {
      asked.append("    - id: ").append(capability).append('\n');
      asked.append("      reason: \"Because the test says so\"\n");
      if ("network:outbound".equals(capability)) {
        asked.append("      hosts: [\"example.org\"]\n");
      }
    }
    return """
        apiVersion: home-inv.plugin/v1
        metadata:
          id: %s
          name: "A plugin that asks"
          version: "1.0.0"
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
        %s"""
        .formatted(PLUGIN, asked)
        .getBytes(StandardCharsets.UTF_8);
  }

  /**
   * An administrator of a fresh tenant.
   *
   * <p>The first owner of a tenant holds every permission, which is what provisioning gives them,
   * so this session may both read and consent.
   *
   * @param name distinguishes this test's tenant from the others'
   * @return the signed-in session
   * @throws Exception when provisioning or signing in fails
   */
  private MockHttpSession anAdministrator(String name) throws Exception {
    String email = "plugin-consent-" + name + "@example.org";
    UUID userId = UUID.randomUUID();
    transactions.executeWithoutResult(
        status ->
            users.save(
                AppUser.create(
                    userId, email, "Owner", "en", passwordEncoder.encode(PASSWORD), Instant.now())));
    enrolSecondFactor(userId);
    provisioning.provision("Consenting " + name, userId);
    return signIn(email, PASSWORD);
  }
}
