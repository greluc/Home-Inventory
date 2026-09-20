/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tenancy.infrastructure;

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
 * What {@code tenancy} puts in an export: the tenant and who belongs to it (REQ-PORT-006).
 *
 * <h2>The membership is the personal datum</h2>
 *
 * <p>"Which people had access to this inventory, in what role, since when" is exactly the kind of
 * statement Art. 15 asks an operator to be able to produce, and it exists nowhere else — an item's
 * {@code created_by} names an id and says nothing about what that person was allowed to do.
 *
 * <h2>What stays behind</h2>
 *
 * <ul>
 *   <li>{@code invitation} — an open invitation holds a token and the address of somebody who is
 *       <b>not</b> a member. Carrying it would put a third party's address in a file handed to
 *       somebody else, and the token would still open a door on the instance being left.
 *   <li>{@code tenant_quota} and {@code quota_usage} — limits the operator set and a counter
 *       derived from the rows that are in the archive anyway. The receiving instance has its own
 *       limits, and importing somebody else's would be importing a policy rather than data.
 *   <li>{@code erasure_certificate} — the record that an erasure was carried out. It is evidence
 *       for the operator, it survives the tenant it describes on purpose, and a copy of it proves
 *       nothing away from the instance that issued it.
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class TenancyExport implements ExportSource {

  private static final String TENANT =
      """
      select id, name, locale, lifecycle_state,
             created_at, updated_at, created_by, updated_by, deleted_at, version
      from tenancy.tenant
      order by id
      """;

  private static final String MEMBERSHIPS =
      """
      select id, user_id, role, role_definition_id, scope_location_id,
             created_at, updated_at, created_by, updated_by, deleted_at, version
      from tenancy.membership
      order by created_at, id
      """;

  private final JdbcClient jdbc;

  @Override
  public String block() {
    return "tenancy";
  }

  @Override
  @Transactional(readOnly = true)
  public void exportTo(Sink sink) {
    sink.write("tenant", jdbc.sql(TENANT).query(TenancyExport::row).list().stream());
    sink.write("memberships", jdbc.sql(MEMBERSHIPS).query(TenancyExport::row).list().stream());
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
