/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.platform.NotFoundException;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.plugins.api.PluginSettings;
import de.greluc.homeinv.plugins.application.DefaultPluginRegistry;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What a tenant configured for a plugin (REQ-PLG-017, ADR-0073).
 *
 * <p>The manifest has declared {@code settings} since 09 §9.3 was written and there was no way for
 * a plugin to receive one: no table, no endpoint, no field on the wire. What is asserted here is the
 * shape the gap was closed with, and the four properties that make it safe to close it that way:
 *
 * <ul>
 *   <li>the <b>manifest decides</b> what may be stored — a key it does not declare is refused, and
 *       so is a value outside a declared list;
 *   <li>a <b>secret is sealed at rest</b> and never comes back out of a surface;
 *   <li>a setting belongs to <b>one tenant</b>, like every other row in this system;
 *   <li>what a plugin receives is the tenant's value, or the manifest's default, or nothing.
 * </ul>
 */
@DisplayName("A plugin's settings")
class PluginSettingsIT extends AbstractIntegrationTest {

  private static final String PLUGIN = "de.greluc.homeinv.plugin.test.settings";

  @Autowired private PluginSettings settings;
  @Autowired private DefaultPluginRegistry registry;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private AppUserRepository users;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private JdbcClient jdbc;

  @Test
  @DisplayName("reach a call as the tenant's value, the manifest's default, or not at all")
  void whatThePluginReceives() {
    UUID tenant = aTenant("receives@example.org");
    registry.register(manifest(), "settings:9000", null, true);

    TenantContext.runAs(
        tenant,
        () -> {
          registry.grant(PLUGIN, "core:setting:read", UUID.randomUUID());
          // Nothing set: the manifest's own default, and nothing for the two
          // settings that have none.
          assertThat(settings.effective(PLUGIN, tenant))
              .containsExactly(Map.entry("preferredSource", "openlibrary"));

          settings.set(PLUGIN, "preferredSource", "dnb", UUID.randomUUID());
          settings.set(PLUGIN, "retries", "3", UUID.randomUUID());
          settings.set(PLUGIN, "apiToken", "a-token-nobody-else-has", UUID.randomUUID());

          assertThat(settings.effective(PLUGIN, tenant))
              .containsOnly(
                  Map.entry("preferredSource", "dnb"),
                  Map.entry("retries", "3"),
                  // Opened on the way out: a plugin receives the value, not the
                  // ciphertext.
                  Map.entry("apiToken", "a-token-nobody-else-has"));
        });
  }

  @Test
  @DisplayName("keep a secret sealed at rest and out of every surface")
  void aSecretIsSealed() {
    UUID tenant = aTenant("sealed@example.org");
    registry.register(manifest(), "settings:9000", null, true);

    TenantContext.runAs(
        tenant, () -> settings.set(PLUGIN, "apiToken", "a-token-nobody-else-has", UUID.randomUUID()));

    // Read inside the tenant's context AND inside a transaction, because both are
    // what makes a row visible here: `SET LOCAL app.tenant_id` is applied when a
    // transaction begins, so a bare statement sees a session with no tenant and
    // the policy yields nothing -- which is the right answer and not the one this
    // test is asking.
    String stored =
        TenantContext.callAs(
            tenant,
            () ->
                transactions.execute(
                    status ->
                        jdbc.sql(
                        """
                        select value from plugins.plugin_setting
                        where plugin_id = ? and setting_key = 'apiToken'
                        """)
                            .param(PLUGIN)
                            .query(String.class)
                            .single()));

    // Not the value, and not a transformation anybody could reverse without the
    // tenant's data key (ADR-0019).
    assertThat(stored).isNotEqualTo("a-token-nobody-else-has").doesNotContain("token");

    TenantContext.runAs(
        tenant,
        () ->
            assertThat(settings.configured(PLUGIN))
                .filteredOn(setting -> "apiToken".equals(setting.key()))
                .singleElement()
                .satisfies(
                    setting -> {
                      // A surface learns that one is stored and never what it is.
                      assertThat(setting.value()).isNull();
                      assertThat(setting.set()).isTrue();
                    }));
  }

  @Test
  @DisplayName("refuse a key the manifest does not declare, and a value outside a declared list")
  void theManifestDecides() {
    UUID tenant = aTenant("refuses@example.org");
    registry.register(manifest(), "settings:9000", null, true);

    TenantContext.runAs(
        tenant,
        () -> {
          assertThatThrownBy(
                  () -> settings.set(PLUGIN, "somethingElse", "value", UUID.randomUUID()))
              .isInstanceOf(IllegalArgumentException.class)
              .hasMessageContaining("somethingElse");

          assertThatThrownBy(() -> settings.set(PLUGIN, "preferredSource", "wikipedia", UUID.randomUUID()))
              .isInstanceOf(IllegalArgumentException.class)
              .hasMessageContaining("openlibrary");

          assertThatThrownBy(() -> settings.set(PLUGIN, "retries", "many", UUID.randomUUID()))
              .isInstanceOf(IllegalArgumentException.class)
              .hasMessageContaining("integer");
        });
  }

  @Test
  @DisplayName("belong to one tenant, like every other row here")
  void settingsArePerTenant() {
    UUID mine = aTenant("mine-settings@example.org");
    UUID theirs = aTenant("theirs-settings@example.org");
    registry.register(manifest(), "settings:9000", null, true);

    TenantContext.runAs(
        mine,
        () -> {
          registry.grant(PLUGIN, "core:setting:read", UUID.randomUUID());
          settings.set(PLUGIN, "apiToken", "mine-alone", UUID.randomUUID());
        });
    TenantContext.runAs(
        theirs, () -> registry.grant(PLUGIN, "core:setting:read", UUID.randomUUID()));

    assertThat(TenantContext.callAs(mine, () -> settings.effective(PLUGIN, mine)))
        .containsEntry("apiToken", "mine-alone");
    // The same plugin, installed once, configured by each tenant for itself.
    assertThat(TenantContext.callAs(theirs, () -> settings.effective(PLUGIN, theirs)))
        .doesNotContainKey("apiToken");
  }

  @Test
  @DisplayName("are answered about a plugin nobody installed with not-found, never an empty list")
  void anUnknownPluginIsNotAnEmptyAnswer() {
    UUID tenant = aTenant("unknown-plugin@example.org");

    TenantContext.runAs(
        tenant,
        () -> {
          assertThatThrownBy(() -> settings.configured("de.greluc.homeinv.plugin.test.nothing"))
              .isInstanceOf(NotFoundException.class);
          // And clearing one, which would otherwise answer "done" about something
          // that does not exist (REQ-SEC-025).
          assertThatThrownBy(
                  () ->
                      settings.clear(
                          "de.greluc.homeinv.plugin.test.nothing", "apiToken", UUID.randomUUID()))
              .isInstanceOf(NotFoundException.class);
        });
  }

  @Test
  @DisplayName("go back to the manifest's default when they are cleared")
  void clearingRestoresTheDefault() {
    UUID tenant = aTenant("cleared@example.org");
    registry.register(manifest(), "settings:9000", null, true);

    TenantContext.runAs(
        tenant,
        () -> {
          registry.grant(PLUGIN, "core:setting:read", UUID.randomUUID());
          settings.set(PLUGIN, "preferredSource", "dnb", UUID.randomUUID());
          settings.clear(PLUGIN, "preferredSource", UUID.randomUUID());
          assertThat(settings.effective(PLUGIN, tenant))
              .containsEntry("preferredSource", "openlibrary");
          // Clearing what was never set is not an error: what the caller wants is
          // already true.
          settings.clear(PLUGIN, "preferredSource", UUID.randomUUID());
        });
  }

  @Test
  @DisplayName("travel only once the tenant has granted core:setting:read")
  void withoutTheGrantAPluginIsSentNothing() {
    UUID tenant = aTenant("ungranted-settings@example.org");
    registry.register(manifest(), "settings:9000", null, true);

    TenantContext.runAs(
        tenant,
        () -> {
          settings.set(PLUGIN, "apiToken", "configured-and-not-consented-to", UUID.randomUUID());

          // Configured, and the tenant has agreed to nothing. `core:setting:read`
          // no longer means "the plugin may fetch them" -- nothing fetches -- it
          // means the core may SEND them, which keeps REQ-PLG-005 true for the
          // one kind of value a tenant is most likely to mind.
          assertThat(settings.effective(PLUGIN, tenant)).isEmpty();

          registry.grant(PLUGIN, "core:setting:read", UUID.randomUUID());
          assertThat(settings.effective(PLUGIN, tenant))
              .containsEntry("apiToken", "configured-and-not-consented-to");
        });
  }

  // -------------------------------------------------------------------------

  /**
   * A manifest declaring one of each interesting setting type.
   *
   * @return the manifest bytes
   */
  private static byte[] manifest() {
    return """
        apiVersion: home-inv.plugin/v1
        metadata:
          id: %s
          name: "A plugin with settings"
          version: "1.0.0"
          vendor: "greluc"
          license: "Apache-2.0"
        spec:
          contract: ">=1.0.0 <2.0.0"
          runtime: out-of-process
          implements:
            - port: MetadataResolver
              priority: 100
          capabilities:
            - id: core:setting:read
              reason: "Reading what this tenant configured"
          settings:
            - key: preferredSource
              type: enum
              values: [openlibrary, dnb]
              default: openlibrary
              label: { en: "Preferred source" }
            - key: retries
              type: integer
              label: { en: "How many times to try" }
            - key: apiToken
              type: secret
              required: false
              label: { en: "Access token" }
        """
        .formatted(PLUGIN)
        .getBytes(StandardCharsets.UTF_8);
  }

  private UUID aTenant(String email) {
    UUID userId = UUID.randomUUID();
    transactions.executeWithoutResult(
        status ->
            users.save(
                AppUser.create(
                    userId,
                    email,
                    "Operator",
                    "en",
                    passwordEncoder.encode("correct-horse-battery-staple-42"),
                    Instant.now())));
    return provisioning.provision("Tenant of " + email, userId);
  }
}
