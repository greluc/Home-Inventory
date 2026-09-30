/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.authorization.infrastructure;

import de.greluc.homeinv.portability.api.ExportSource;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * What {@code authorization} puts in an export: the roles a tenant defined for itself
 * (REQ-PORT-006).
 *
 * <p>These travel because a membership points at one. {@code tenancy.membership.role_definition_id}
 * is a tenant-owned role, and an archive carrying the membership without the definition would carry
 * a pointer at nothing — the same defect as items written against type definitions the receiving
 * instance does not have (REQ-PORT-004). The permissions come with it for the same reason: a role
 * with no permission rows is a name, and a name is not what the membership meant.
 *
 * <p>{@code field_visibility} is here too. It is the rule that decides who may read a field marked
 * {@code sensitive}, so leaving it behind would import an inventory whose most closely held values
 * are readable by whoever the receiving instance's defaults let in — a configuration change nobody
 * asked for, arriving silently.
 *
 * <p>Nothing here is a credential: a permission is a string like {@code item.read}, and the
 * strings are the same on every instance.
 */
@Component
@RequiredArgsConstructor
public class AuthorizationExport implements ExportSource {

  private static final String ROLES =
      """
      select id, name, description, base_role,
             created_at, updated_at, created_by, updated_by, deleted_at, version
      from authz.role_definition
      order by name, id
      """;

  private static final String PERMISSIONS =
      """
      select id, role_definition_id, permission,
             created_at, updated_at, created_by, updated_by, deleted_at, version
      from authz.role_permission
      order by role_definition_id, permission
      """;

  private static final String FIELD_VISIBILITY =
      """
      select id, field_key, built_in_role, role_definition_id,
             created_at, updated_at, created_by, updated_by, deleted_at, version
      from authz.field_visibility
      order by field_key, id
      """;

  private final JdbcClient jdbc;

  @Override
  public String block() {
    return "authorization";
  }

  @Override
  @Transactional(readOnly = true)
  public void exportTo(Sink sink) {
    sink.write("role-definitions", jdbc.sql(ROLES).query(AuthorizationExport::row).list().stream());
    sink.write(
        "role-permissions", jdbc.sql(PERMISSIONS).query(AuthorizationExport::row).list().stream());
    sink.write(
        "field-visibility",
        jdbc.sql(FIELD_VISIBILITY).query(AuthorizationExport::row).list().stream());
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
    ResultSetMetaData meta = rs.getMetaData();
    for (int column = 1; column <= meta.getColumnCount(); column++) {
      row.put(meta.getColumnLabel(column), rs.getObject(column));
    }
    return row;
  }
}
