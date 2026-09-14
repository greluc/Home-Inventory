/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.plugins.infrastructure;

import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.plugins.api.PluginRegistry.Grant;
import de.greluc.homeinv.plugins.api.PluginRegistry.Registration;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The statements behind the plugin registry (REQ-PLG-004…006).
 *
 * <p>Registrations are instance-wide and carry no {@code tenant_id}; grants are per tenant and
 * every statement over them names it, so row-level security is the second line and not the only one
 * (ADR-0003).
 */
@Component
@RequiredArgsConstructor
public class PluginRegistryQueries {

  private final JdbcClient jdbc;

  /**
   * Every installed plugin, by id.
   *
   * @return the registrations
   */
  @Transactional(readOnly = true)
  public List<Registration> installed() {
    return jdbc
        .sql(
            """
            select plugin_id, plugin_version, manifest, manifest_digest, capabilities, contract,
                   runtime, signed, state, created_at
            from plugins.plugin_registration
            order by plugin_id
            """)
        .query(PluginRegistryQueries::toRegistration)
        .list();
  }

  /**
   * One installed plugin.
   *
   * @param pluginId which one
   * @return it, or empty when nothing is installed under that id
   */
  @Transactional(readOnly = true)
  public Optional<Registration> registration(String pluginId) {
    return jdbc
        .sql(
            """
            select plugin_id, plugin_version, manifest, manifest_digest, capabilities, contract,
                   runtime, signed, state, created_at
            from plugins.plugin_registration
            where plugin_id = ?
            """)
        .param(pluginId)
        .query(PluginRegistryQueries::toRegistration)
        .optional();
  }

  /**
   * Writes a registration, or brings an existing one up to date.
   *
   * <p>An upsert on the plugin id, because an operator restarting the stack with the same plugin is
   * the ordinary case and a second row for it would be a second plugin. What changes on an update
   * is the manifest and everything derived from it; {@code registered_at} does not, because the
   * plugin has been here since it first was.
   *
   * <p><b>The grants are not touched.</b> A manifest asking for more leaves what was granted
   * granted and the new capability ungranted, which is what REQ-PLG-006 means by "never escalates
   * silently" (decided with the owner, 2026-09-14).
   *
   * @param registration what to write
   * @param manifest the manifest bytes as they were read
   * @param endpoint where the plugin listens, or {@code null} for an in-process one
   * @param fingerprint the certificate that may answer there, or {@code null}
   */
  @Transactional
  public void register(
      Registration registration, String manifest, String endpoint, String fingerprint) {
    jdbc.sql(
            """
            insert into plugins.plugin_registration
                (plugin_id, plugin_version, manifest, manifest_digest, capabilities, contract,
                 runtime, endpoint, fingerprint, signed, state)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (plugin_id) do update set
                plugin_version = excluded.plugin_version,
                manifest = excluded.manifest,
                manifest_digest = excluded.manifest_digest,
                capabilities = excluded.capabilities,
                contract = excluded.contract,
                runtime = excluded.runtime,
                endpoint = excluded.endpoint,
                fingerprint = excluded.fingerprint,
                signed = excluded.signed,
                updated_at = now(),
                version = plugins.plugin_registration.version + 1
            """)
        .params(
            registration.pluginId(),
            registration.version(),
            manifest,
            registration.manifestDigest(),
            registration.capabilities().toArray(String[]::new),
            registration.contract(),
            registration.runtime(),
            endpoint,
            fingerprint,
            registration.signed(),
            registration.disabled() ? "DISABLED" : "REGISTERED")
        .update();
  }

  /**
   * What this tenant has granted one plugin.
   *
   * @param pluginId which plugin
   * @return the grants
   */
  @Transactional(readOnly = true)
  public List<Grant> grants(String pluginId) {
    return jdbc
        .sql(
            """
            select plugin_id, capability, manifest_digest, created_at, created_by
            from plugins.capability_grant
            where tenant_id = ? and plugin_id = ?
            order by capability
            """)
        .params(TenantContext.require(), pluginId)
        .query(PluginRegistryQueries::toGrant)
        .list();
  }

  /**
   * Whether this tenant has granted this plugin this capability.
   *
   * @param pluginId which plugin
   * @param capability which capability
   * @return true when a grant exists
   */
  @Transactional(readOnly = true)
  public boolean granted(String pluginId, String capability) {
    return !jdbc
        .sql(
            """
            select 1
            from plugins.capability_grant
            where tenant_id = ? and plugin_id = ? and capability = ?
            """)
        .params(TenantContext.require(), pluginId, capability)
        .query(Integer.class)
        .list()
        .isEmpty();
  }

  /**
   * Records a consent.
   *
   * <p>Idempotent on the unique key: consenting twice is one grant, and the second says so by
   * changing nothing. The digest is <b>not</b> updated on the second, because what the row records
   * is which manifest was agreed to.
   *
   * @param pluginId which plugin
   * @param capability which capability
   * @param manifestDigest what the administrator was looking at
   * @param actor who agreed
   */
  @Transactional
  public void grant(String pluginId, String capability, String manifestDigest, UUID actor) {
    jdbc.sql(
            """
            insert into plugins.capability_grant
                (tenant_id, plugin_id, capability, manifest_digest, created_by)
            values (?, ?, ?, ?, ?)
            on conflict (tenant_id, plugin_id, capability) do nothing
            """)
        .params(TenantContext.require(), pluginId, capability, manifestDigest, actor)
        .update();
  }

  /**
   * Withdraws a consent.
   *
   * @param pluginId which plugin
   * @param capability which capability
   * @return how many rows went, which is one or none
   */
  @Transactional
  public int revoke(String pluginId, String capability) {
    return jdbc
        .sql(
            """
            delete from plugins.capability_grant
            where tenant_id = ? and plugin_id = ? and capability = ?
            """)
        .params(TenantContext.require(), pluginId, capability)
        .update();
  }

  private static Registration toRegistration(ResultSet rs, int rowNum) throws SQLException {
    Array capabilities = rs.getArray("capabilities");
    return new Registration(
        rs.getString("plugin_id"),
        // The name and the vendor are read back out of the manifest by the
        // application layer, which is the one place that parses it. This row
        // carries the manifest itself so that nothing has to be stored twice.
        null,
        rs.getString("plugin_version"),
        null,
        rs.getString("contract"),
        rs.getString("runtime"),
        capabilities == null ? List.of() : List.of((String[]) capabilities.getArray()),
        rs.getString("manifest_digest"),
        rs.getBoolean("signed"),
        "DISABLED".equals(rs.getString("state")),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }

  /**
   * The manifest of one registration, as it was read.
   *
   * @param pluginId which plugin
   * @return the manifest, or empty when nothing is installed under that id
   */
  @Transactional(readOnly = true)
  public Optional<String> manifest(String pluginId) {
    return jdbc
        .sql("select manifest from plugins.plugin_registration where plugin_id = ?")
        .param(pluginId)
        .query(String.class)
        .optional();
  }

  private static Grant toGrant(ResultSet rs, int rowNum) throws SQLException {
    return new Grant(
        rs.getString("plugin_id"),
        rs.getString("capability"),
        rs.getString("manifest_digest"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("created_by", UUID.class));
  }
}
