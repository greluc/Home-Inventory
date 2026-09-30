/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.plugins.infrastructure;

import de.greluc.homeinv.portability.api.ExportSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * What {@code plugins} puts in an export: what this tenant configured, minus what nobody may read
 * (REQ-PORT-006, REQ-PLG-017).
 *
 * <h2>The settings travel; the secrets do not, and the archive says so</h2>
 *
 * <p>A setting is configuration a person wrote, and lost work if it does not travel:
 * <i>this</i> tenant chose that source, those retries, that endpoint. It goes in.
 *
 * <p>A <b>secret</b> does not, and this is the one place in the archive where ADR-0068's rule
 * — unseal what the requester may read — resolves to <i>nothing</i>. No API returns a plugin
 * secret to anybody, not to the administrator who set it and not to an owner: the surface says a
 * value is stored and offers to replace it. An archive that opened one would be a way to read a
 * value the system does not otherwise hand out, which is the definition of a leak however
 * legitimate the request that produced it.
 *
 * <p>So the row travels with its key and without its value, and the archive <b>names what was
 * withheld</b> rather than presenting a setting that looks configured and is empty.
 *
 * <h2>What is not here</h2>
 *
 * <p>{@code plugin_registration} and the two grant tables. A registration is the <b>operator's</b>
 * description of this deployment (REQ-PLG-013) and belongs to no tenant; a grant is consent given
 * to a plugin <i>on this instance</i>, and consent that travelled to another instance would be
 * consent nobody gave there. Both are named in {@code ExportCoverageIT}'s own list with that
 * reason.
 */
@Component
@RequiredArgsConstructor
public class PluginSettingExport implements ExportSource {

  private static final String SETTINGS =
      """
      select id, plugin_id, setting_key,
             case when sealed then null else value end as value,
             sealed, created_at, updated_at, created_by, updated_by, version
      from plugins.plugin_setting
      order by plugin_id, setting_key
      """;

  private static final String SEALED_COUNT =
      """
      select count(*) from plugins.plugin_setting where sealed
      """;

  private final JdbcClient jdbc;

  @Override
  public String block() {
    return "plugins";
  }

  @Override
  @Transactional(readOnly = true)
  public void exportTo(Sink sink) {
    sink.write(
        "plugin-settings", jdbc.sql(SETTINGS).query(PluginSettingExport::row).list().stream());

    long sealed = jdbc.sql(SEALED_COUNT).query(Long.class).single();
    if (sealed > 0) {
      sink.withheld(
          sealed + " plugin setting(s) marked secret",
          "A plugin secret is returned by no interface of this system, to anybody: a surface says"
              + " one is stored and offers to replace it. The key travels so that the setting can"
              + " be recognised and set again on the instance you move to; the value stays here,"
              + " because an archive that opened it would hand out what nothing else does.");
    }
  }

  /**
   * One row as an ordered map, carrying the stored column names.
   *
   * @param rs the row
   * @param rowNum which row
   * @return the row
   * @throws SQLException when it cannot be read
   */
  private static Map<String, Object> row(ResultSet rs, int rowNum) throws SQLException {
    Map<String, Object> row = new LinkedHashMap<>();
    java.sql.ResultSetMetaData meta = rs.getMetaData();
    for (int column = 1; column <= meta.getColumnCount(); column++) {
      row.put(meta.getColumnLabel(column), rs.getObject(column));
    }
    return row;
  }
}
