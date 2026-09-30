/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.notification.infrastructure;

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
 * What {@code notification} puts in an export: what a tenant asked to be told about, and where
 * (REQ-PORT-006).
 *
 * <h2>The rules, not the deliveries</h2>
 *
 * <p>A reminder rule is configuration a person wrote — "warn me thirty days before a warranty
 * runs out" — and it is lost work if it does not travel. A {@code subscription} carries an
 * <b>address</b>, which is personal data under any reading of Art. 15 and belongs in the archive
 * for that reason alone.
 *
 * <p>What is not here is the log: {@code notification}, {@code reminder} and {@code
 * delivery_attempt} are a record of messages that were sent by <i>this</i> deployment. They are
 * append-only history of an operation, they say nothing the rules do not, and re-importing them
 * would either produce a second copy of every message already delivered or a table of deliveries
 * that never happened on the instance holding it.
 *
 * <h2>Security notifications stay where they were raised</h2>
 *
 * <p>{@code security_notification} and its delivery attempts are the record that somebody was told
 * their password had been reset or their session ended somewhere unfamiliar. That is evidence
 * about the instance being left, its value is that it sits beside the audit trail there, and a
 * copy in an archive is a copy of a security record with none of what makes it one.
 */
@Component
@RequiredArgsConstructor
public class NotificationExport implements ExportSource {

  private static final String RULES =
      """
      select id, name, trigger_kind, saved_search_id, offset_days, channel_key, enabled,
             created_at, updated_at, created_by, updated_by, version
      from notification.notification_rule
      order by name, id
      """;

  private static final String SUBSCRIPTIONS =
      """
      select id, user_id, kind, channel_key, address, enabled,
             created_at, updated_at, created_by, updated_by, version
      from notification.subscription
      order by user_id, kind, channel_key
      """;

  private final JdbcClient jdbc;

  @Override
  public String block() {
    return "notification";
  }

  @Override
  @Transactional(readOnly = true)
  public void exportTo(Sink sink) {
    sink.write("reminder-rules", jdbc.sql(RULES).query(NotificationExport::row).list().stream());
    sink.write(
        "subscriptions", jdbc.sql(SUBSCRIPTIONS).query(NotificationExport::row).list().stream());
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
