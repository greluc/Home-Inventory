/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.search.infrastructure;

import de.greluc.homeinv.portability.api.ExportSource;
import java.sql.Array;
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
 * What {@code search} puts in an export: the searches somebody saved, never the index
 * (REQ-PORT-006).
 *
 * <p>A saved search is written work — a filter somebody built once and uses weekly — and it is the
 * one thing in this block that exists only because a person made it. The index is the opposite:
 * OpenSearch is derived and rebuildable, and an archive assembled from it would be subtly wrong
 * exactly when it was lagging. A reminder rule may also point at a saved search
 * ({@code notification_rule.saved_search_id}), so leaving these behind would import rules that
 * trigger on nothing.
 */
@Component
@RequiredArgsConstructor
public class SearchExport implements ExportSource {

  private static final String SAVED =
      """
      select id, name, query_text, filters, sort,
             created_at, updated_at, created_by, updated_by, version
      from search.saved_search
      order by name, id
      """;

  private final JdbcClient jdbc;

  @Override
  public String block() {
    return "search";
  }

  @Override
  @Transactional(readOnly = true)
  public void exportTo(Sink sink) {
    sink.write("saved-searches", jdbc.sql(SAVED).query(SearchExport::row).list().stream());
  }

  /**
   * One row as an ordered map, carrying the stored column names.
   *
   * <p>{@code filters} is a {@code text[]}, and the driver hands those over as a {@link Array}
   * whose default serialisation is a JDBC handle rather than its contents. It is unwrapped here so
   * the archive holds a JSON array of strings — the shape an import can read without knowing which
   * driver wrote it.
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
      Object value = rs.getObject(column);
      if (value instanceof Array array) {
        value = array.getArray();
      }
      row.put(meta.getColumnLabel(column), value);
    }
    return row;
  }
}
