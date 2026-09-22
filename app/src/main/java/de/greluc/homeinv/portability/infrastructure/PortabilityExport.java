/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.portability.infrastructure;

import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.portability.api.ExportSource;
import de.greluc.homeinv.portability.api.ImportSql;
import de.greluc.homeinv.portability.api.ImportTarget;
import de.greluc.homeinv.portability.api.Remapping;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * What {@code portability} itself puts in an export, and reads back (REQ-PORT-002, REQ-PORT-006).
 *
 * <h2>The mapping profiles a tenant wrote</h2>
 *
 * <p>Lost work otherwise, and the same argument as a saved search: somebody sat down and worked out
 * which column of their spreadsheet means what, and that is theirs. The two that <b>ship</b> —
 * Homebox and InvenTree — are constants rather than rows and are therefore neither exported nor
 * imported: they are already on the other instance.
 *
 * <h2>What is not here</h2>
 *
 * <p>{@code export_job} and {@code import_job} stay behind, for the reason an archive cannot carry
 * the record of its own making. So does {@code import_provenance}: every row of it points at an
 * import job, and on the receiving instance there is no such job — the provenance of an item is a
 * fact about the instance it was imported into, and it is made afresh there by the import that
 * brings it.
 */
@Component
@RequiredArgsConstructor
public class PortabilityExport implements ExportSource, ImportTarget {

  private static final List<String> PROFILE =
      List.of(
          "id", "key", "name", "source", "column_map", "default_currency", "item_type_key",
          "created_at", "updated_at", "created_by", "updated_by", "version");

  private static final String PROFILES =
      """
      select id, key, name, source, column_map::text as column_map, default_currency,
             item_type_key, created_at, updated_at, created_by, updated_by, version
      from portability.mapping_profile
      order by key
      """;

  /** Columns an export wrote through {@code ::text} and an import has to unwrap again. */
  private static final Set<String> JSON_COLUMNS = Set.of("column_map");

  private final JdbcClient jdbc;
  private final ObjectMapper json;

  @Override
  public String block() {
    return "portability";
  }

  @Override
  public int order() {
    // Last, and it could be first: a mapping profile points at nothing. Behind
    // everything else so that a failure in it is the last thing to happen rather
    // than the thing that stops the inventory arriving.
    return 70;
  }

  @Override
  @Transactional(readOnly = true)
  public void exportTo(Sink sink) {
    sink.write("mapping-profiles", jdbc.sql(PROFILES).query(PortabilityExport::row).list().stream());
  }

  @Override
  public Outcome importFrom(Archive archive, Remapping ids) {
    int inserted = 0;
    int overwritten = 0;
    for (Map<String, Object> profile : archive.rows("portability", "mapping-profiles")) {
      boolean fresh =
          Boolean.TRUE.equals(
              jdbc.sql(
                      ImportSql.upsert("portability.mapping_profile", PROFILE, "tenant_id, id"))
                  .param(TenantContext.require())
                  .param(ImportSql.asJson(json, profile, JSON_COLUMNS))
                  .query(Boolean.class)
                  .single());
      if (fresh) {
        inserted++;
      } else {
        overwritten++;
      }
    }
    return new Outcome(inserted, overwritten, 0);
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
