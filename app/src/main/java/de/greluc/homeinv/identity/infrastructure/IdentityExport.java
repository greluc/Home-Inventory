/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.identity.infrastructure;

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
 * What {@code identity} puts in an export: the people, and nothing that authenticates them
 * (REQ-PORT-006).
 *
 * <h2>Whose accounts</h2>
 *
 * <p>{@code identity.app_user} is the one table in the archive that is <b>not</b> tenant-scoped: an
 * account may be a member of several tenants, so the row carries no {@code tenant_id} and no
 * row-level policy limits it. The export therefore limits it itself, through this tenant's
 * memberships — which <i>are</i> tenant-scoped, so the subquery is already narrowed by the policy
 * that protects them. An archive that read {@code app_user} without the join would hand its owner
 * every account on the instance, and it would do so quietly.
 *
 * <h2>Nothing from the credential path</h2>
 *
 * <p>No {@code password_hash}, no {@code password_changed_at}, and none of {@code credential},
 * {@code password_reset} or {@code service_account} at all. An export is handed to a person and
 * then copied — onto a laptop, into a cloud drive, through a support ticket — and a password hash
 * in it is one somebody can grind offline at their leisure. The receiving instance issues its own
 * credentials; what travels is who the people are, not how they prove it.
 */
@Component
@RequiredArgsConstructor
public class IdentityExport implements ExportSource {

  /**
   * The accounts that are members of this tenant.
   *
   * <p>{@code deleted_at is null} is deliberately absent from the membership predicate: an account
   * whose membership was ended still wrote the items that carry its id in {@code created_by}, and
   * an archive that dropped it would leave every one of those pointing at nobody.
   */
  private static final String USERS =
      """
      select u.id, u.email, u.display_name, u.locale, u.locked_at,
             u.created_at, u.updated_at, u.created_by, u.updated_by, u.deleted_at, u.version
      from identity.app_user u
      where u.id in (select m.user_id from tenancy.membership m)
      order by u.created_at, u.id
      """;

  private final JdbcClient jdbc;

  @Override
  public String block() {
    return "identity";
  }

  @Override
  @Transactional(readOnly = true)
  public void exportTo(Sink sink) {
    sink.write("users", jdbc.sql(USERS).query(IdentityExport::row).list().stream());
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
