/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.catalog.infrastructure;

import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.portability.api.ImportSql;
import de.greluc.homeinv.portability.api.ImportTarget;
import de.greluc.homeinv.portability.api.Remapping;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads the type system back out of an archive (REQ-PORT-004).
 *
 * <h2>Matched by key, not by id — and this is why the import needs a remapping at all</h2>
 *
 * <p>Every tenant is provisioned with the same built-in item types, location categories and value
 * lists, and every copy has <b>its own id</b>. So an archive carries a type {@code general} with one
 * id while the receiving tenant already holds a type {@code general} with another, and {@code UNIQUE
 * (tenant_id, key)} allows only one of them. Inserting the archive's copy fails outright; ignoring
 * it leaves every imported item pointing at a type that is not there.
 *
 * <p>This block therefore matches on the <b>natural key</b> at each of the three levels — the key of
 * a type, the version number under it, the key of a field under that — updates what it finds, and
 * records in {@link Remapping} what the archive's id turned into. Every later block resolves its
 * references through that. It runs first ({@link #order()} 10) because everything else depends on
 * the answers.
 *
 * <p>The archive still wins: a matched row is overwritten from it, column for column, keeping only
 * the id it already had here.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CatalogImport implements ImportTarget {

  private static final List<String> ITEM_TYPE =
      List.of(
          "id", "key", "parent_id", "kind", "icon", "builtin", "archived_at",
          "created_at", "updated_at", "created_by", "updated_by", "version");

  private static final List<String> ITEM_TYPE_VERSION =
      List.of(
          "id", "item_type_id", "version_number", "json_schema", "published_at",
          "created_at", "updated_at", "created_by", "updated_by", "version");

  private static final List<String> FIELD =
      List.of(
          "id", "item_type_version_id", "location_category_version_id", "key", "data_type",
          "labels", "help_texts", "required", "default_value", "constraints", "value_list_id",
          "visibility", "field_group", "display_order", "searchable", "sortable", "facetable",
          "sensitive", "expiry", "deprecated_at", "inherited_from",
          "created_at", "updated_at", "created_by", "updated_by", "version");

  private static final List<String> CATEGORY =
      List.of(
          "id", "key", "labels", "icon", "is_mobile", "builtin", "archived_at",
          "created_at", "updated_at", "created_by", "updated_by", "version");

  private static final List<String> CATEGORY_VERSION =
      List.of(
          "id", "location_category_id", "version_number", "json_schema", "published_at",
          "created_at", "updated_at", "created_by", "updated_by", "version");

  private static final List<String> CATEGORY_CHILD =
      List.of("parent_category_id", "child_category_id", "created_at", "created_by");

  private static final List<String> VALUE_LIST =
      List.of(
          "id", "key", "labels", "builtin", "archived_at",
          "created_at", "updated_at", "created_by", "updated_by", "version");

  private static final List<String> VALUE_LIST_ENTRY =
      List.of(
          "id", "value_list_id", "value", "labels", "display_order", "archived_at",
          "created_at", "updated_at", "created_by", "updated_by", "version");

  /** Columns an export wrote through {@code ::text} and an import has to unwrap again. */
  private static final Set<String> JSON_COLUMNS =
      Set.of("labels", "help_texts", "default_value", "constraints", "visibility", "json_schema");

  private final JdbcClient jdbc;
  private final ObjectMapper json;

  @Override
  public String block() {
    return "catalog";
  }

  @Override
  public int order() {
    return 10;
  }

  @Override
  public Outcome importFrom(Archive archive, Remapping ids) {
    Outcome outcome = Outcome.NOTHING;
    outcome = outcome.plus(itemTypes(archive, ids));
    outcome = outcome.plus(itemTypeVersions(archive, ids));
    outcome = outcome.plus(categories(archive, ids));
    outcome = outcome.plus(categoryVersions(archive, ids));
    outcome = outcome.plus(valueLists(archive, ids));
    outcome = outcome.plus(valueListEntries(archive, ids));
    outcome = outcome.plus(fields(archive, ids));
    outcome = outcome.plus(categoryChildren(archive, ids));
    return outcome;
  }

  /**
   * Item types, matched by key.
   *
   * <p>{@code parent_id} is written in a second pass. A type may sit under another and the archive
   * is ordered by key rather than by depth, so a single pass would insert a child before its parent
   * and fail on the self-referencing foreign key. Two statements are cheaper than a topological
   * sort and impossible to get subtly wrong.
   *
   * @param archive the archive
   * @param ids where the remapping is recorded
   * @return what happened
   */
  private Outcome itemTypes(Archive archive, Remapping ids) {
    List<Map<String, Object>> rows = archive.rows("catalog", "item-types");
    Outcome outcome =
        keyed(rows, ids, "catalog.item_type", ITEM_TYPE, this::typeByKey, List.of(), "parent_id");
    for (Map<String, Object> row : rows) {
      UUID parent = ids.resolve(ImportSql.uuid(row.get("parent_id")));
      if (parent != null) {
        jdbc.sql("update catalog.item_type set parent_id = ? where id = ?")
            .param(parent)
            .param(ids.resolve(ImportSql.uuid(row.get("id"))))
            .update();
      }
    }
    return outcome;
  }

  private Optional<UUID> typeByKey(Map<String, Object> row, Remapping ids) {
    return jdbc
        .sql("select id from catalog.item_type where key = ?")
        .param(ImportSql.text(row.get("key")))
        .query(UUID.class)
        .optional();
  }

  private Outcome itemTypeVersions(Archive archive, Remapping ids) {
    return keyed(
        archive.rows("catalog", "item-type-versions"),
        ids,
        "catalog.item_type_version",
        ITEM_TYPE_VERSION,
        (row, map) ->
            jdbc
                .sql(
                    """
                    select id from catalog.item_type_version
                    where item_type_id = ? and version_number = ?
                    """)
                .param(map.resolve(ImportSql.uuid(row.get("item_type_id"))))
                .param(ImportSql.number(row.get("version_number")))
                .query(UUID.class)
                .optional(),
        List.of("item_type_id"),
        null);
  }

  private Outcome categories(Archive archive, Remapping ids) {
    return keyed(
        archive.rows("catalog", "location-categories"),
        ids,
        "catalog.location_category",
        CATEGORY,
        (row, map) ->
            jdbc
                .sql("select id from catalog.location_category where key = ?")
                .param(ImportSql.text(row.get("key")))
                .query(UUID.class)
                .optional(),
        List.of(),
        null);
  }

  private Outcome categoryVersions(Archive archive, Remapping ids) {
    return keyed(
        archive.rows("catalog", "location-category-versions"),
        ids,
        "catalog.location_category_version",
        CATEGORY_VERSION,
        (row, map) ->
            jdbc
                .sql(
                    """
                    select id from catalog.location_category_version
                    where location_category_id = ? and version_number = ?
                    """)
                .param(map.resolve(ImportSql.uuid(row.get("location_category_id"))))
                .param(ImportSql.number(row.get("version_number")))
                .query(UUID.class)
                .optional(),
        List.of("location_category_id"),
        null);
  }

  private Outcome valueLists(Archive archive, Remapping ids) {
    return keyed(
        archive.rows("catalog", "value-lists"),
        ids,
        "catalog.value_list",
        VALUE_LIST,
        (row, map) ->
            jdbc
                .sql("select id from catalog.value_list where key = ?")
                .param(ImportSql.text(row.get("key")))
                .query(UUID.class)
                .optional(),
        List.of(),
        null);
  }

  private Outcome valueListEntries(Archive archive, Remapping ids) {
    return keyed(
        archive.rows("catalog", "value-list-entries"),
        ids,
        "catalog.value_list_entry",
        VALUE_LIST_ENTRY,
        (row, map) ->
            jdbc
                .sql(
                    """
                    select id from catalog.value_list_entry
                    where value_list_id = ? and value = ?
                    """)
                .param(map.resolve(ImportSql.uuid(row.get("value_list_id"))))
                .param(ImportSql.text(row.get("value")))
                .query(UUID.class)
                .optional(),
        List.of("value_list_id"),
        null);
  }

  /**
   * Field definitions, matched by the key they carry under their version.
   *
   * <p>Both owning columns are remapped: a field belongs either to an item type version or to a
   * location category version, and exactly one of the two is set ({@code
   * field_definition_one_owner}).
   *
   * @param archive the archive
   * @param ids the remapping
   * @return what happened
   */
  private Outcome fields(Archive archive, Remapping ids) {
    return keyed(
        archive.rows("catalog", "field-definitions"),
        ids,
        "catalog.field_definition",
        FIELD,
        (row, map) -> {
          UUID typeVersion = map.resolve(ImportSql.uuid(row.get("item_type_version_id")));
          UUID categoryVersion = map.resolve(ImportSql.uuid(row.get("location_category_version_id")));
          return jdbc
              .sql(
                  """
                  select id from catalog.field_definition
                  where key = ?
                    and item_type_version_id is not distinct from ?
                    and location_category_version_id is not distinct from ?
                  """)
              .param(ImportSql.text(row.get("key")))
              .param(typeVersion)
              .param(categoryVersion)
              .query(UUID.class)
              .optional();
        },
        List.of("item_type_version_id", "location_category_version_id", "value_list_id"),
        null);
  }

  /**
   * Which category may sit inside which — a pure link table with no id of its own.
   *
   * @param archive the archive
   * @param ids the remapping
   * @return what happened
   */
  private Outcome categoryChildren(Archive archive, Remapping ids) {
    int inserted = 0;
    int skipped = 0;
    for (Map<String, Object> row : archive.rows("catalog", "location-category-children")) {
      Map<String, Object> resolved = new LinkedHashMap<>(row);
      resolved.put("parent_category_id", ids.resolve(ImportSql.uuid(row.get("parent_category_id"))));
      resolved.put("child_category_id", ids.resolve(ImportSql.uuid(row.get("child_category_id"))));
      int written =
          jdbc.sql(
                  ImportSql.insert("catalog.location_category_child", CATEGORY_CHILD)
                      + " on conflict do nothing")
              .param(TenantContext.require())
              .param(ImportSql.asJson(json, resolved, JSON_COLUMNS))
              .update();
      if (written > 0) {
        inserted++;
      } else {
        skipped++;
      }
    }
    return new Outcome(inserted, 0, skipped);
  }

  /**
   * One table's rows, matched by a natural key rather than by id.
   *
   * @param rows the archive's rows
   * @param ids where a remapping is recorded
   * @param table the qualified table
   * @param columns its columns
   * @param lookup how to find the local row this archive row corresponds to
   * @param references columns holding an id that has to be resolved before the row is written
   * @param deferred a column left null in this pass because a later one fills it, or null
   * @return what happened
   */
  private Outcome keyed(
      List<Map<String, Object>> rows,
      Remapping ids,
      String table,
      List<String> columns,
      Lookup lookup,
      List<String> references,
      String deferred) {
    int inserted = 0;
    int overwritten = 0;
    for (Map<String, Object> row : rows) {
      Map<String, Object> resolved = new LinkedHashMap<>(row);
      for (String reference : references) {
        resolved.put(reference, ids.resolve(ImportSql.uuid(row.get(reference))));
      }
      if (deferred != null) {
        resolved.put(deferred, null);
      }
      String body = ImportSql.asJson(json, resolved, JSON_COLUMNS);

      Optional<UUID> local = lookup.find(row, ids);
      if (local.isPresent()) {
        ids.remap(ImportSql.uuid(row.get("id")), local.get());
        jdbc.sql(ImportSql.update(table, columns)).param(body).param(local.get()).update();
        overwritten++;
      } else {
        jdbc.sql(ImportSql.insert(table, columns))
            .param(TenantContext.require())
            .param(body)
            .update();
        inserted++;
      }
    }
    return new Outcome(inserted, overwritten, 0);
  }

  /** How one table finds the local row an archive row corresponds to. */
  @FunctionalInterface
  private interface Lookup {

    /**
     * The local row's id, if it is there.
     *
     * @param row the archive row
     * @param ids the remapping, for resolving the owning reference first
     * @return the id, or empty
     */
    Optional<UUID> find(Map<String, Object> row, Remapping ids);
  }

}
