/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.plugins.infrastructure;

import de.greluc.homeinv.platform.TenantContext;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The statements behind the per-tenant plugin settings (ADR-0073, REQ-PLG-017).
 *
 * <p>Every one of them names the tenant, so row-level security is the second line and not the only
 * one (ADR-0003). What is stored here is what a <b>tenant</b> configured; the operator's own
 * configuration of a plugin container never reaches the core at all.
 */
@Component
@RequiredArgsConstructor
public class PluginSettingQueries {

  private final JdbcClient jdbc;

  /**
   * What this tenant stored for one plugin.
   *
   * @param pluginId the plugin
   * @return one row per configured setting, by key
   */
  @Transactional(readOnly = true)
  public List<Stored> of(String pluginId) {
    return jdbc
        .sql(
            """
            select setting_key, value, sealed
            from plugins.plugin_setting
            where tenant_id = ? and plugin_id = ?
            order by setting_key
            """)
        .params(TenantContext.require(), pluginId)
        .query((rs, row) -> new Stored(rs.getString("setting_key"), rs.getString("value"), rs.getBoolean("sealed")))
        .list();
  }

  /**
   * Writes one value, replacing what was there.
   *
   * <p>An upsert rather than a delete and an insert: a setting is one thing a tenant configures, and
   * two statements would leave a window in which it is configured to nothing.
   *
   * @param pluginId the plugin
   * @param key the setting
   * @param value the value, sealed already when it is a secret
   * @param sealed whether it is
   * @param actor who configured it
   */
  @Transactional
  public void put(String pluginId, String key, String value, boolean sealed, UUID actor) {
    jdbc.sql(
            """
            insert into plugins.plugin_setting
                (tenant_id, plugin_id, setting_key, value, sealed, created_by)
            values (?, ?, ?, ?, ?, ?)
            on conflict (tenant_id, plugin_id, setting_key) do update set
                value = excluded.value,
                sealed = excluded.sealed,
                updated_at = now(),
                updated_by = excluded.created_by,
                version = plugins.plugin_setting.version + 1
            """)
        .params(TenantContext.require(), pluginId, key, value, sealed, actor)
        .update();
  }

  /**
   * Removes one value.
   *
   * @param pluginId the plugin
   * @param key the setting
   * @return whether a row was there to remove
   */
  @Transactional
  public boolean remove(String pluginId, String key) {
    return jdbc
            .sql(
                """
                delete from plugins.plugin_setting
                where tenant_id = ? and plugin_id = ? and setting_key = ?
                """)
            .params(TenantContext.require(), pluginId, key)
            .update()
        > 0;
  }

  /**
   * One stored value.
   *
   * @param key the setting key
   * @param value what is stored — ciphertext when {@code sealed}
   * @param sealed whether the value is sealed
   */
  public record Stored(String key, String value, boolean sealed) {}
}
