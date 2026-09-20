/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.locations.infrastructure;

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
 * What {@code locations} puts in an export (REQ-PORT-003).
 *
 * <p>The tree, as it is stored. {@code path} goes with it and {@code depth} does not need to: both
 * are derived from {@code parent_id}, and an import that trusted a stored {@code path} would
 * inherit a corruption rather than notice one. It is written all the same, because a person reading
 * the archive wants to see where a thing was without reconstructing the tree in their head.
 *
 * <p>{@code deleted_at} is kept: an archive is of everything, including what is in the trash, and a
 * tenant moving instance should not silently lose the things they had not decided about yet.
 */
@Component
@RequiredArgsConstructor
public class LocationExport implements ExportSource {

  /**
   * Written out whole and column by column.
   *
   * <p>Never {@code select *}: a column added later would join the archive without anybody deciding
   * it should, and this file is the one place that decision belongs.
   *
   * <p>*It named {@code category_id} at first, which V5 created and V14 <b>dropped</b> when a
   * location started pointing at a category VERSION. Reading a migration's original DDL without its
   * successors is how a column that has not existed for fifty migrations ends up in a query.*
   */
  private static final String LOCATIONS =
      """
      select id, category_version_id, parent_id, name, path::text as path, depth,
             is_mobile, attributes::text as attributes, sealed_at,
             created_at, updated_at, created_by, deleted_at, version
      from locations.location
      order by path, id
      """;

  private final JdbcClient jdbc;

  @Override
  public String block() {
    return "locations";
  }

  @Override
  @Transactional(readOnly = true)
  public void exportTo(Sink sink) {
    sink.write("locations", jdbc.sql(LOCATIONS).query(LocationExport::row).list().stream());
  }

  /**
   * One row as an ordered map.
   *
   * <p>A map rather than a record, and the archive therefore carries the <b>stored</b> shape with
   * its column names. An export is a data archive rather than an API payload: the receiving side
   * wants the rows, and a translation into the API's spelling would be a second shape to keep in
   * step with the first.
   *
   * <p>Materialised per dataset for now. The port takes a {@link java.util.stream.Stream} so a
   * source can become lazy without anything else changing, which is the point of its being a stream
   * even while this one is not.
   *
   * @param rs the row
   * @param rowNum which row
   * @return the row, in the column order above
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
