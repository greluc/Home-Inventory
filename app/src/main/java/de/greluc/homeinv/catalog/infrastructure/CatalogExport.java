/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.catalog.infrastructure;

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
 * What {@code catalog} puts in an export (REQ-PORT-004).
 *
 * <p>This is the block REQ-PORT-004 is about: "the export contains the type definitions, so that a
 * tenant can move entirely". An archive of items written against types the receiving instance does
 * not have is an archive of nothing — the attributes are a JSONB object whose keys mean whatever
 * the field definitions say they mean, and without them they mean nothing at all.
 *
 * <p>Versions and their fields go too, not only what is published today. An item references the
 * type <b>version</b> it was written against (ADR-0004), so an archive carrying only the current
 * one would leave every item written against an earlier version pointing at nothing.
 */
@Component
@RequiredArgsConstructor
public class CatalogExport implements ExportSource {

  private static final String ITEM_TYPES =
      """
      select id, key, parent_id, kind, icon, builtin, archived_at,
             created_at, updated_at, created_by, updated_by, version
      from catalog.item_type
      order by key, id
      """;

  private static final String ITEM_TYPE_VERSIONS =
      """
      select id, item_type_id, version_number, json_schema::text as json_schema, published_at,
             created_at, updated_at, created_by, updated_by, version
      from catalog.item_type_version
      order by item_type_id, version_number
      """;

  private static final String FIELDS =
      """
      select id, item_type_version_id, location_category_version_id, key, data_type,
             labels::text as labels, help_texts::text as help_texts, required,
             default_value::text as default_value, constraints::text as constraints,
             value_list_id, visibility::text as visibility, field_group, display_order,
             searchable, sortable, facetable, sensitive, expiry, deprecated_at, inherited_from,
             created_at, updated_at, created_by, updated_by, version
      from catalog.field_definition
      order by item_type_version_id, location_category_version_id, display_order, key
      """;

  private static final String CATEGORIES =
      """
      select id, key, labels::text as labels, icon, is_mobile, builtin, archived_at,
             created_at, updated_at, created_by, updated_by, version
      from catalog.location_category
      order by key, id
      """;

  private static final String CATEGORY_VERSIONS =
      """
      select id, location_category_id, version_number, json_schema::text as json_schema,
             published_at, created_at, updated_at, created_by, updated_by, version
      from catalog.location_category_version
      order by location_category_id, version_number
      """;

  private static final String CATEGORY_CHILDREN =
      """
      select parent_category_id, child_category_id, created_at, created_by
      from catalog.location_category_child
      order by parent_category_id, child_category_id
      """;

  private static final String VALUE_LISTS =
      """
      select id, key, labels::text as labels, builtin, archived_at,
             created_at, updated_at, created_by, updated_by, version
      from catalog.value_list
      order by key, id
      """;

  private static final String VALUE_LIST_ENTRIES =
      """
      select id, value_list_id, value, labels::text as labels, display_order, archived_at,
             created_at, updated_at, created_by, updated_by, version
      from catalog.value_list_entry
      order by value_list_id, display_order, value
      """;

  private final JdbcClient jdbc;

  @Override
  public String block() {
    return "catalog";
  }

  @Override
  @Transactional(readOnly = true)
  public void exportTo(Sink sink) {
    sink.write("item-types", jdbc.sql(ITEM_TYPES).query(CatalogExport::row).list().stream());
    sink.write(
        "item-type-versions", jdbc.sql(ITEM_TYPE_VERSIONS).query(CatalogExport::row).list().stream());
    sink.write("field-definitions", jdbc.sql(FIELDS).query(CatalogExport::row).list().stream());
    sink.write("location-categories", jdbc.sql(CATEGORIES).query(CatalogExport::row).list().stream());
    sink.write(
        "location-category-versions",
        jdbc.sql(CATEGORY_VERSIONS).query(CatalogExport::row).list().stream());
    sink.write(
        "location-category-children",
        jdbc.sql(CATEGORY_CHILDREN).query(CatalogExport::row).list().stream());
    sink.write("value-lists", jdbc.sql(VALUE_LISTS).query(CatalogExport::row).list().stream());
    sink.write(
        "value-list-entries", jdbc.sql(VALUE_LIST_ENTRIES).query(CatalogExport::row).list().stream());
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
