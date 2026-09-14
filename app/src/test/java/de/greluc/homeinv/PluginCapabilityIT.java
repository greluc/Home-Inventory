/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.plugins.api.PluginRegistry;
import de.greluc.homeinv.plugins.application.DefaultPluginRegistry;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What a plugin may do, and in whose tenant (REQ-PLG-005, REQ-PLG-006, 09 §9.4).
 *
 * <p>Four properties, and each of them is a sentence from the chapter rather than an invention:
 *
 * <ul>
 *   <li>a plugin has <b>no</b> permissions until they are granted — no base entitlement;
 *   <li>a grant belongs to <b>one tenant</b>: installed for everybody, permitted by each;
 *   <li>a manifest that asks for <b>more</b> takes nothing away and grants nothing new;
 *   <li>a grant for a capability the current manifest no longer declares is a <b>leftover</b> and
 *       not a permission.
 * </ul>
 */
@DisplayName("A plugin's capabilities")
class PluginCapabilityIT extends AbstractIntegrationTest {

  private static final String PLUGIN = "de.greluc.homeinv.plugin.isbn";

  @Autowired private DefaultPluginRegistry registry;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private AppUserRepository users;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;

  @Test
  @DisplayName("are nothing at all until a tenant grants them")
  void nothingUntilGranted() {
    UUID tenant = aTenant("nothing@example.org");
    registry.register(manifest("1.0.0", "core:item:read"), "isbn:9000", null, true);

    TenantContext.runAs(
        tenant,
        () -> {
          // Installed, and permitted nothing. There is no base entitlement and no
          // "read access, which is harmless anyway" (09 §9.4).
          assertThat(registry.installed()).extracting(PluginRegistry.Registration::pluginId)
              .contains(PLUGIN);
          assertThat(registry.permits(PLUGIN, "core:item:read")).isFalse();

          registry.grant(PLUGIN, "core:item:read", UUID.randomUUID());
          assertThat(registry.permits(PLUGIN, "core:item:read")).isTrue();
        });
  }

  @Test
  @DisplayName("belong to one tenant: installed for everybody, permitted by each")
  void grantsArePerTenant() {
    UUID mine = aTenant("mine@example.org");
    UUID theirs = aTenant("theirs@example.org");
    registry.register(manifest("1.0.0", "core:item:read"), "isbn:9000", null, true);

    TenantContext.runAs(mine, () -> registry.grant(PLUGIN, "core:item:read", UUID.randomUUID()));

    assertThat(TenantContext.callAs(mine, () -> registry.permits(PLUGIN, "core:item:read")))
        .isTrue();
    // The same plugin, installed once, and invisible to the other tenant until
    // its own administrator says yes.
    assertThat(TenantContext.callAs(theirs, () -> registry.permits(PLUGIN, "core:item:read")))
        .isFalse();
  }

  @Test
  @DisplayName("survive an update that asks for more, and the new one is not granted with them")
  void anUpdateThatAsksForMore() {
    UUID tenant = aTenant("more@example.org");
    registry.register(manifest("1.0.0", "core:item:read"), "isbn:9000", null, true);
    TenantContext.runAs(tenant, () -> registry.grant(PLUGIN, "core:item:read", UUID.randomUUID()));

    // Version 2 wants the network as well.
    registry.register(manifest("2.0.0", "core:item:read", "core:item:write"), "isbn:9000", null, true);

    TenantContext.runAs(
        tenant,
        () -> {
          // What was granted is still granted: the plugin carries on with what it
          // has rather than being disabled by an update nobody asked about.
          assertThat(registry.permits(PLUGIN, "core:item:read")).isTrue();
          // And what is new is not: nothing escalates without somebody saying yes
          // (REQ-PLG-006).
          assertThat(registry.permits(PLUGIN, "core:item:write")).isFalse();
        });
  }

  @Test
  @DisplayName("stop when the manifest stops asking for them, grant or no grant")
  void aGrantForSomethingNoLongerAskedFor() {
    UUID tenant = aTenant("dropped@example.org");
    registry.register(manifest("1.0.0", "core:item:read", "core:item:write"), "isbn:9000", null, true);
    TenantContext.runAs(tenant, () -> registry.grant(PLUGIN, "core:item:write", UUID.randomUUID()));

    // Version 2 no longer asks to write.
    registry.register(manifest("2.0.0", "core:item:read"), "isbn:9000", null, true);

    // The grant is still in the table, and it is not a permission: capabilities
    // are exhaustive, so what the manifest does not name cannot happen even with
    // consent granted (09 §9.3).
    assertThat(TenantContext.callAs(tenant, () -> registry.permits(PLUGIN, "core:item:write")))
        .isFalse();
  }

  @Test
  @DisplayName("cannot be granted for something the plugin never asked for")
  void consentToSomethingUnasked() {
    UUID tenant = aTenant("unasked@example.org");
    registry.register(manifest("1.0.0", "core:item:read"), "isbn:9000", null, true);

    assertThatThrownBy(
            () ->
                TenantContext.runAs(
                    tenant, () -> registry.grant(PLUGIN, "core:item:write", UUID.randomUUID())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("core:item:write");
  }

  @Test
  @DisplayName("are withdrawable, and withdrawing what was never granted is not an error")
  void revoking() {
    UUID tenant = aTenant("revoke@example.org");
    registry.register(manifest("1.0.0", "core:item:read"), "isbn:9000", null, true);

    TenantContext.runAs(
        tenant,
        () -> {
          registry.grant(PLUGIN, "core:item:read", UUID.randomUUID());
          registry.revoke(PLUGIN, "core:item:read", UUID.randomUUID());
          assertThat(registry.permits(PLUGIN, "core:item:read")).isFalse();
          // The outcome a caller wants is "this plugin may not do this here", and
          // that is already true.
          registry.revoke(PLUGIN, "core:item:read", UUID.randomUUID());
        });
  }

  // -------------------------------------------------------------------------

  /**
   * A manifest for the test plugin, declaring exactly these capabilities.
   *
   * @param version the plugin's version
   * @param capabilities what it asks for
   * @return the manifest bytes, which is what a registration is made from
   */
  private static byte[] manifest(String version, String... capabilities) {
    StringBuilder asked = new StringBuilder();
    for (String capability : capabilities) {
      asked.append("    - id: ").append(capability).append('\n');
      asked.append("      reason: \"Because the test says so\"\n");
    }
    String document =
        """
        apiVersion: home-inv.plugin/v1
        metadata:
          id: %s
          name: "ISBN metadata"
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
        %s"""
            .formatted(PLUGIN, version, asked);
    return document.getBytes(StandardCharsets.UTF_8);
  }

  private UUID aTenant(String email) {
    UUID userId = UUID.randomUUID();
    transactions.executeWithoutResult(
        status ->
            users.save(
                AppUser.create(
                    userId, email, "Operator", "en", passwordEncoder.encode("irrelevant"), Instant.now())));
    return provisioning.provision("Plugins of " + email, userId);
  }
}
