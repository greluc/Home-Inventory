/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.locations.infrastructure;

import de.greluc.homeinv.catalog.api.AttributeRedaction;
import de.greluc.homeinv.portability.api.ExportSource;
import java.sql.ResultSet;
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
             created_at, updated_at, created_by, updated_by, deleted_at, version
      from locations.location
      order by path, id
      """;

  private final JdbcClient jdbc;

  /**
   * Opens the sealed values the requester may read, and removes the rest.
   *
   * <p>The same port the API uses. Which fields are sensitive is a fact about the type system and
   * whether this caller may read one is {@code authorization}'s answer, so an export that decided
   * either for itself would be a second implementation of a rule that must not have two.
   */
  private final AttributeRedaction redaction;

  private final ObjectMapper json;

  @Override
  public String block() {
    return "locations";
  }

  @Override
  @Transactional(readOnly = true)
  public void exportTo(Sink sink) {
    List<Map<String, Object>> places = jdbc.sql(LOCATIONS).query(LocationExport::row).list();
    Set<String> withheld = new TreeSet<>();
    for (Map<String, Object> place : places) {
      // A place has attributes too, written against its category's version, and
      // one of its fields can be marked sensitive just as an item's can. Sealed
      // values are opened as far as the person who asked may read them; the rest
      // are named in the manifest rather than left looking like empty fields.
      if (place.get("category_version_id") instanceof UUID categoryVersion
          && place.get("id") instanceof UUID id
          && place.get("attributes") instanceof String stored
          && !stored.isBlank()) {
        String opened = redaction.forCaller(categoryVersion, id, stored);
        if (!opened.equals(stored)) {
          withheld.addAll(keysLost(json, stored, opened));
          place.put("attributes", opened);
        }
      }
    }
    sink.write("locations", places.stream());
    withheld.forEach(
        key ->
            sink.withheld(
                key,
                "A field marked sensitive that the person who asked for this archive may not read, "
                    + "or whose second factor was not recently proved (REQ-AUTH-011). The value "
                    + "stays in the instance this archive came from."));
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
