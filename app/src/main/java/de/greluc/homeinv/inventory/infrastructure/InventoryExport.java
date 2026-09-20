/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.infrastructure;

import de.greluc.homeinv.catalog.api.AttributeRedaction;
import de.greluc.homeinv.portability.api.ExportSource;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * What {@code inventory} puts in an export (REQ-PORT-003).
 *
 * <p>Five datasets, and the omissions are the interesting part:
 *
 * <ul>
 *   <li>{@code search_vector_de} and {@code search_vector_en} are <b>generated</b> columns. They
 *       are the stored form of what the row already says, and an import would recompute them
 *       anyway — exporting them would be exporting an opinion about a text analyser;
 *   <li>{@code item_attr_index} is <b>derived</b> (ADR-0004, variant D): JSONB is the source of
 *       truth and the side table is rebuilt from it. An archive carrying both would carry one fact
 *       twice, and the copy that was wrong would be the one somebody trusted;
 *   <li>{@code attributes} <b>is</b> carried, as text. It is the source of truth, and it is what
 *       makes an item mean anything on the other side.
 * </ul>
 *
 * <p>Read from PostgreSQL and never from the search index — the index is rebuildable and may be
 * lagging, and an archive assembled from one would be subtly wrong exactly when it mattered.
 */
@Component
@RequiredArgsConstructor
public class InventoryExport implements ExportSource {

  /** Column by column, never {@code select *}: a new column joins the archive by decision only. */
  private static final String ITEMS =
      """
      select id, item_type_version_id, name, description, kind, location_id, quantity,
             quantity_unit, lifecycle_state, attributes::text as attributes, notes, minimum_stock,
             purchase_amount, purchase_currency, purchased_on, purchase_source,
             warranty_until, lifetime_warranty,
             replacement_amount, replacement_currency, replacement_as_of, replacement_source,
             current_amount, current_currency, current_as_of,
             disposal_amount, disposal_currency, disposed_on, disposal_recipient, disposal_note,
             maintenance_interval_days,
             created_at, updated_at, created_by, updated_by, deleted_at, version
      from inventory.item
      order by created_at, id
      """;

  private static final String MAINTENANCE =
      """
      select id, item_id, performed_on, kind, cost_amount, cost_currency, note,
             created_at, created_by
      from inventory.maintenance_entry
      order by item_id, performed_on, id
      """;

  private static final String LOANS =
      """
      select id, item_id, borrower_user_id, borrower_name, handed_out_on, due_on, returned_on,
             note, created_at, created_by, updated_at, updated_by, version
      from inventory.loan
      order by item_id, handed_out_on, id
      """;

  private static final String RELATIONS =
      """
      select id, source_id, target_id, relation_type, created_at, created_by, version
      from inventory.item_relation
      order by source_id, target_id, relation_type
      """;

  private static final String BUNDLES =
      """
      select id, bundle_item_id, member_item_id, created_at, created_by, version
      from inventory.item_bundle
      order by bundle_item_id, member_item_id
      """;

  private final JdbcClient jdbc;

  /**
   * Opens the sealed values the requester may read, and removes the rest.
   *
   * <p>The same port the API uses, for the same reason: which fields are sensitive is a fact about
   * the type system, and whether this caller may read one is {@code authorization}'s answer. An
   * export deciding either for itself would be a second implementation of a rule that must not have
   * two.
   */
  private final AttributeRedaction redaction;

  private final ObjectMapper json;

  @Override
  public String block() {
    return "inventory";
  }

  @Override
  @Transactional(readOnly = true)
  public void exportTo(Sink sink) {
    List<Map<String, Object>> items = jdbc.sql(ITEMS).query(InventoryExport::row).list();
    Set<String> withheld = new TreeSet<>();
    for (Map<String, Object> item : items) {
      // A sealed value is unreadable anywhere but here (ADR-0019), so carrying
      // the ciphertext would be carrying data the receiving instance cannot
      // open -- lost on the way out, with nothing having failed. It is opened
      // for the person who asked, as far as they may read, and what is left
      // over is named in the manifest rather than left looking empty.
      if (item.get("item_type_version_id") instanceof UUID typeVersion
          && item.get("id") instanceof UUID id
          && item.get("attributes") instanceof String stored
          && !stored.isBlank()) {
        String opened = redaction.forCaller(typeVersion, id, stored);
        if (!opened.equals(stored)) {
          withheld.addAll(keysLost(json, stored, opened));
          item.put("attributes", opened);
        }
      }
    }
    sink.write("items", items.stream());
    withheld.forEach(
        key ->
            sink.withheld(
                key,
                "A field marked sensitive that the person who asked for this archive may not read, "
                    + "or whose second factor was not recently proved (REQ-AUTH-011). The value "
                    + "stays in the instance this archive came from."));
    sink.write("maintenance-entries", jdbc.sql(MAINTENANCE).query(InventoryExport::row).list().stream());
    sink.write("loans", jdbc.sql(LOANS).query(InventoryExport::row).list().stream());
    sink.write("relations", jdbc.sql(RELATIONS).query(InventoryExport::row).list().stream());
    sink.write("bundles", jdbc.sql(BUNDLES).query(InventoryExport::row).list().stream());
  }

  /**
   * Which keys an attribute set lost on the way through redaction.
   *
   * <p>Compared as parsed objects rather than as text: a key removed by looking for its name in a
   * string is a key removed until somebody's value happens to contain that name.
   *
   * @param json the mapper
   * @param before the stored attributes
   * @param after what the caller may see of them
   * @return the keys that are no longer there
   */
  private static Set<String> keysLost(ObjectMapper json, String before, String after) {
    Set<String> lost = new TreeSet<>();
    JsonNode stored = json.readTree(before);
    JsonNode shown = json.readTree(after);
    if (!stored.isObject() || !shown.isObject()) {
      return lost;
    }
    stored
        .propertyNames()
        .forEach(
            key -> {
              if (!shown.has(key)) {
                lost.add(key);
              }
            });
    return lost;
  }

  /**
   * One row as an ordered map, carrying the stored column names.
   *
   * <p>An export is a data archive rather than an API payload, so it says {@code purchase_amount}
   * where the API says {@code purchase.amount}. Translating would be a second shape to keep in step
   * with the first, and the receiving side wants the rows.
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
