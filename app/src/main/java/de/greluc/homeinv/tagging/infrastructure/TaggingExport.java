/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tagging.infrastructure;

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
 * What {@code tagging} puts in an export (REQ-PORT-003).
 *
 * <p>Tags, their groups and their assignments. {@code merged_into} goes with them: a tag that was
 * merged keeps its own name so history reads correctly (REQ-CORE-063), and an archive that dropped
 * the pointer would turn a redirect into a duplicate on the other side.
 */
@Component
@RequiredArgsConstructor
public class TaggingExport implements ExportSource {

  private static final String TAGS =
      """
      select id, name, tag_group_id, colour, icon, merged_into,
             created_at, updated_at, created_by, updated_by, version
      from tagging.tag
      order by name, id
      """;

  private static final String GROUPS =
      """
      select id, key, labels::text as labels, exclusive, display_order,
             created_at, updated_at, created_by, updated_by, version
      from tagging.tag_group
      order by display_order, key
      """;

  private static final String ASSIGNMENTS =
      """
      select id, tag_id, item_id, location_id,
             created_at, updated_at, created_by, updated_by, version
      from tagging.tag_assignment
      order by tag_id, item_id, location_id
      """;

  private final JdbcClient jdbc;

  @Override
  public String block() {
    return "tagging";
  }

  @Override
  @Transactional(readOnly = true)
  public void exportTo(Sink sink) {
    sink.write("tags", jdbc.sql(TAGS).query(TaggingExport::row).list().stream());
    sink.write("tag-groups", jdbc.sql(GROUPS).query(TaggingExport::row).list().stream());
    sink.write("tag-assignments", jdbc.sql(ASSIGNMENTS).query(TaggingExport::row).list().stream());
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
