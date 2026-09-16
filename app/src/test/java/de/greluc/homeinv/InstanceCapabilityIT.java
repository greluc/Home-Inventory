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

import de.greluc.homeinv.identity.api.AccountAdministration;
import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.platform.TenantContext;
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
 * The second level of the capability model (ADR-0066, REQ-NOTI-004).
 *
 * <p>The deployment owes an account some mail — a password reset, a new second factor, a remote
 * sign-out — and owes it whether or not the account belongs to a tenant. Every part of the plugin
 * path was per tenant, so the instance operator gained a grant of their own.
 *
 * <p>What this test is really about is the <b>separation</b> of the two levels. A widening is only
 * as narrow as its boundaries, so the boundaries are what is asserted: a tenant's consent does not
 * become the instance's, a capability nobody asked for is refused here as well, and neither level
 * is reachable by somebody not entitled to it. That an instance call then reaches a plugin and
 * carries no tenant is proved where a plugin is actually running, in {@code PluginRuntimeIT}.
 */
@DisplayName("Granting a capability for the instance")
class InstanceCapabilityIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple-42";
  private static final String PLUGIN = "de.greluc.homeinv.plugin.instancemail";
  private static final String INSTANCE_PLUGINS = "/api/v1/instance/plugins";

  @Autowired private DefaultPluginRegistry registry;
  @Autowired private AccountAdministration accounts;
  @Autowired private AppUserRepository users;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private ObjectMapper json;

  /** The account created by the most recent {@link #anOperator} call. */
  private UUID operatorId;

  /** The tenant provisioned for it. */
  private UUID operatorTenant;

  @Test
  @DisplayName("records it, lists it, and withdraws it again")
  void grantingAndWithdrawing() throws Exception {
    MockHttpSession operator = anOperator("cycle");
    register();

    // Nothing is granted to begin with. There is no base entitlement on this
    // level either (09 §9.4).
    assertThat(grantedForInstance(operator)).isFalse();

    grant(operator).andExpect(status().isNoContent());
    assertThat(registry.permitsForInstance(PLUGIN, "network:outbound")).isTrue();

    // Twice is once, as on the tenant path: the second says so by succeeding and
    // changing nothing.
    grant(operator).andExpect(status().isNoContent());
    assertThat(registry.instanceGrants(PLUGIN)).hasSize(1);

    assertThat(grantedForInstance(operator)).isTrue();

    withdraw(operator).andExpect(status().isNoContent());
    assertThat(registry.permitsForInstance(PLUGIN, "network:outbound")).isFalse();

    // Withdrawing what is not there is not an error: what the caller wants is
    // "this plugin may not do this for the instance", and that is already true.
    withdraw(operator).andExpect(status().isNoContent());
  }

  @Test
  @DisplayName("keeps the two levels apart in both directions")
  void theLevelsAreSeparate() throws Exception {
    MockHttpSession operator = anOperator("separate");
    register();

    // A tenant grants everything it can. That says nothing about the instance:
    // the deployment's own obligations are not a tenant's to permit.
    TenantContext.runAs(
        operatorTenant, () -> registry.grant(PLUGIN, "network:outbound", operatorId));
    assertThat(registry.permitsForInstance(PLUGIN, "network:outbound")).isFalse();

    // And the reverse. The instance grants it, the tenant withdraws its own, and
    // the two answers are about the same plugin at the same moment.
    grant(operator).andExpect(status().isNoContent());
    TenantContext.runAs(
        operatorTenant, () -> registry.revoke(PLUGIN, "network:outbound", operatorId));

    assertThat(registry.permitsForInstance(PLUGIN, "network:outbound")).isTrue();
    assertThat(TenantContext.callAs(operatorTenant, () -> registry.permits(PLUGIN, "network:outbound")))
        .isFalse();
  }

  @Test
  @DisplayName("refuses a capability the manifest does not declare")
  void unaskedCapability() throws Exception {
    MockHttpSession operator = anOperator("unasked");
    register();

    // It would be waiting as a permission if the plugin asked for it later,
    // which is the silent escalation REQ-PLG-006 exists to prevent — and the
    // reasoning does not change because the grantor is the operator.
    mockMvc
        .perform(
            put(INSTANCE_PLUGINS + "/" + PLUGIN + "/capabilities/core:item:write")
                .session(operator)
                .with(csrf()))
        .andExpect(status().isUnprocessableContent());
  }

  @Test
  @DisplayName("is out of reach for an account that is not an instance operator")
  void notForEverybody() throws Exception {
    anOperator("gate");
    register();

    MockHttpSession ordinary = anAccount("instance-cap-ordinary");
    mockMvc.perform(get(INSTANCE_PLUGINS).session(ordinary)).andExpect(status().isForbidden());
    mockMvc
        .perform(
            put(INSTANCE_PLUGINS + "/" + PLUGIN + "/capabilities/network:outbound")
                .session(ordinary)
                .with(csrf()))
        .andExpect(status().isForbidden());
  }

  // -------------------------------------------------------------------------

  /**
   * What the operator's listing says about the one capability this plugin asks for.
   *
   * <p>Read out of the document rather than asserted with a nested JSON path: a filter inside a
   * filter answers with a shape that depends on the matcher, and a test that has to be read twice
   * to see what it claims is a test nobody trusts.
   *
   * @param operator the operator's session
   * @return whether the instance has granted {@code network:outbound}
   * @throws Exception when the listing cannot be read
   */
  private boolean grantedForInstance(MockHttpSession operator) throws Exception {
    String body =
        mockMvc
            .perform(get(INSTANCE_PLUGINS).session(operator))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    for (JsonNode plugin : json.readTree(body)) {
      if (!PLUGIN.equals(plugin.get("id").asString())) {
        continue;
      }
      for (JsonNode capability : plugin.get("capabilities")) {
        if ("network:outbound".equals(capability.get("id").asString())) {
          return capability.get("grantedForInstance").asBoolean();
        }
      }
    }
    throw new IllegalStateException("The operator's listing does not show " + PLUGIN);
  }

  private org.springframework.test.web.servlet.ResultActions grant(MockHttpSession operator)
      throws Exception {
    return mockMvc.perform(
        put(INSTANCE_PLUGINS + "/" + PLUGIN + "/capabilities/network:outbound")
            .session(operator)
            .with(csrf()));
  }

  private org.springframework.test.web.servlet.ResultActions withdraw(MockHttpSession operator)
      throws Exception {
    return mockMvc.perform(
        delete(INSTANCE_PLUGINS + "/" + PLUGIN + "/capabilities/network:outbound")
            .session(operator)
            .with(csrf()));
  }

  private void register() {
    registry.register(manifest(), "instancemail:9000", null, true);
  }

  /**
   * A signed-in instance operator, with a tenant of their own.
   *
   * @param name distinguishes this test's account from the others'
   * @return the session
   * @throws Exception when provisioning or signing in fails
   */
  private MockHttpSession anOperator(String name) throws Exception {
    String email = "instance-cap-" + name + "@example.org";
    operatorId = account(email);
    operatorTenant = provisioning.provision("Instance capability " + name, operatorId);
    transactions.executeWithoutResult(
        status -> accounts.replaceEntitlements(operatorId, true, false, null, operatorId));
    return signIn(email, PASSWORD);
  }

  private MockHttpSession anAccount(String name) throws Exception {
    String email = name + "@example.org";
    UUID userId = account(email);
    provisioning.provision("Ordinary " + name, userId);
    return signIn(email, PASSWORD);
  }

  private UUID account(String email) {
    UUID userId = UUID.randomUUID();
    transactions.executeWithoutResult(
        status ->
            users.save(
                AppUser.create(
                    userId,
                    email,
                    email.substring(0, email.indexOf('@')),
                    "en",
                    passwordEncoder.encode(PASSWORD),
                    Instant.now())));
    // REQ-AUTH-003: an OWNER or ADMIN without a second factor is refused every
    // request in the tenant. Proved in SecondFactorIT; here it is a
    // precondition rather than the subject.
    enrolSecondFactor(userId);
    return userId;
  }

  private static byte[] manifest() {
    return """
        apiVersion: home-inv.plugin/v1
        metadata:
          id: %s
          name: "A channel the instance may use"
          version: "1.0.0"
          vendor: "greluc"
          license: "Apache-2.0"
        spec:
          contract: ">=1.0.0 <2.0.0"
          runtime: out-of-process
          implements:
            - port: NotificationChannel
              priority: 100
          capabilities:
            - id: network:outbound
              reason: "It has to reach a mail server"
              hosts: ["smtp.example.org"]
        """
        .formatted(PLUGIN)
        .getBytes(StandardCharsets.UTF_8);
  }
}
