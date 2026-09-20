/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.portability.api;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds the three statements an import needs, from a table and its columns (REQ-PORT-003).
 *
 * <h2>Generated, and still an allowlist</h2>
 *
 * <p>The project's rule is that dynamic SQL goes through a checked builder whose names come from an
 * allowlist rather than from input. That is exactly this: the table name and every column are Java
 * constants declared by the block that owns the table, sitting next to the {@code ExportSource}
 * that writes the same list. Nothing here ever sees a request.
 *
 * <p>The alternative was writing both statements out for each of twenty-one tables — six hundred
 * lines in which the column lists of the export and the import would drift apart on the first
 * change. {@code ExportCoverageIT} would catch one side of that drift and nothing would catch the
 * other.
 *
 * <h2>How a row becomes a row</h2>
 *
 * <p>{@code jsonb_populate_record(null::<table>, ?::jsonb)} turns the archive's JSON object into a
 * record of the table's own type, which means PostgreSQL does every conversion by the column's
 * declared type: a string becomes a {@code timestamptz} or a {@code uuid} or an {@code ltree}
 * because that is what the column is, not because Java guessed. A column the archive does not carry
 * arrives as {@code NULL}.
 *
 * <p>The column list is still written out rather than {@code (r).*}, because {@code (r).*} would
 * include the <b>generated</b> columns — {@code inventory.item.search_vector_de} and its English
 * twin — and inserting into one of those is an error rather than a no-op.
 */
public final class ImportSql {

  /** The longest a table or column name may be, which is PostgreSQL's own limit. */
  private static final int MAX_IDENTIFIER = 63;

  private ImportSql() {}

  /**
   * One identifier, or a refusal.
   *
   * <p>Lower-case letters, digits and underscores, optionally one dot for the schema, and never
   * starting a segment with a digit. Every name these methods put in a statement goes through here
   * — which is what makes this a <b>checked</b> builder in REQ-SEC-031's sense rather than
   * concatenation with a good intention. The names are Java constants today and the check costs
   * nothing; it is here so that the day one arrives from somewhere else, the statement is never
   * built.
   *
   * <p>Written as a loop rather than as a regular expression on purpose: a pattern with a nested
   * quantifier is a ReDoS finding, and a security tool that has to be argued with about a
   * five-character allowlist is a tool nobody reads the output of.
   *
   * @param name a table or column name
   * @return the same name
   * @throws IllegalArgumentException when it is not an identifier this builder will emit
   */
  private static String checked(String name) {
    if (name == null || name.isEmpty() || name.length() > 2 * MAX_IDENTIFIER + 1) {
      throw new IllegalArgumentException("Not a table or column name: " + name);
    }
    boolean startOfSegment = true;
    boolean dotSeen = false;
    for (int at = 0; at < name.length(); at++) {
      char character = name.charAt(at);
      if (character == '.') {
        if (dotSeen || startOfSegment || at == name.length() - 1) {
          throw new IllegalArgumentException("Not a table or column name: " + name);
        }
        dotSeen = true;
        startOfSegment = true;
        continue;
      }
      boolean letter = character >= 'a' && character <= 'z';
      boolean digit = character >= '0' && character <= '9';
      boolean allowed = letter || character == '_' || (digit && !startOfSegment);
      if (!allowed) {
        throw new IllegalArgumentException("Not a table or column name: " + name);
      }
      startOfSegment = false;
    }
    return name;
  }

  /**
   * Several identifiers, or a refusal.
   *
   * @param names table or column names
   * @return the same names
   */
  private static List<String> allChecked(List<String> names) {
    names.forEach(ImportSql::checked);
    return names;
  }

  /**
   * An upsert keyed on the row's own id.
   *
   * <p>Returns whether the row was inserted, through {@code xmax = 0}: a tuple written by the
   * current transaction has no deleting transaction on it, and one updated by {@code ON CONFLICT}
   * does. It is a PostgreSQL internal and it is the only way to learn which branch fired without a
   * second round trip per row.
   *
   * @param table the qualified table, a constant
   * @param columns its columns as the archive carries them, constants
   * @param conflict the unique columns to match on — {@code "tenant_id, id"} for almost everything
   * @return the statement, taking the tenant id and then the row as JSON
   */
  public static String upsert(String table, List<String> columns, String conflict) {
    checked(table);
    allChecked(columns);
    allChecked(java.util.Arrays.stream(conflict.split(",")).map(String::trim).toList());
    String assignments =
        columns.stream()
            .filter(column -> !"id".equals(column))
            .map(column -> column + " = excluded." + column)
            .collect(Collectors.joining(", "));
    // Joined rather than formatted. A text block with `%s` in it is a format
    // string, and a format string containing a line break is a SpotBugs finding
    // (`VA_FORMAT_STRING_USES_NEWLINE`) whose remedy is `%n` -- which is the
    // platform's line separator and would make the statement differ between a
    // developer's machine and the runner for no reason at all.
    return String.join(
        "\n",
        "insert into " + table + " (tenant_id, " + String.join(", ", columns) + ")",
        "select ?::uuid, " + values(columns),
        "from (select jsonb_populate_record(null::" + table + ", ?::jsonb) as r) s",
        "on conflict (" + conflict + ") do update set " + assignments,
        "returning (xmax = 0) as inserted");
  }

  /**
   * A plain insert, for a table matched by something other than its id.
   *
   * @param table the qualified table, a constant
   * @param columns its columns, constants
   * @return the statement, taking the tenant id and then the row as JSON
   */
  public static String insert(String table, List<String> columns) {
    checked(table);
    allChecked(columns);
    return String.join(
        "\n",
        "insert into " + table + " (tenant_id, " + String.join(", ", columns) + ")",
        "select ?::uuid, " + values(columns),
        "from (select jsonb_populate_record(null::" + table + ", ?::jsonb) as r) s");
  }

  /**
   * An update of one row by its local id, from the archive's version of it.
   *
   * <p>{@code id} is never assigned: the row keeps the id it has here. That is the whole point of
   * the natural-key matching in {@code catalog} — the archive's id becomes a {@link Remapping}
   * entry rather than an overwrite.
   *
   * @param table the qualified table, a constant
   * @param columns its columns, constants
   * @return the statement, taking the row as JSON and then the local id
   */
  public static String update(String table, List<String> columns) {
    checked(table);
    allChecked(columns);
    String assignments =
        columns.stream()
            .filter(column -> !"id".equals(column))
            .map(column -> column + " = (r)." + column)
            .collect(Collectors.joining(", "));
    return String.join(
        "\n",
        "update " + table + " set " + assignments,
        "from (select jsonb_populate_record(null::" + table + ", ?::jsonb) as r) s",
        "where " + table + ".id = ?::uuid");
  }

  /**
   * One archive row as the JSON {@code jsonb_populate_record} is given.
   *
   * <p>The one conversion Java has to do. An export writes a {@code jsonb} column through {@code
   * ::text}, so the archive holds the JSON of it as a <b>string</b> — and a string is itself a
   * valid {@code jsonb} value, so feeding it back unchanged would store {@code "{\"en\":\"Box\"}"}
   * where {@code {"en": "Box"}} belongs, silently and with no error anywhere. The columns that need
   * unwrapping are named by the block that owns the table, beside the {@code ::text} casts that
   * caused it.
   *
   * @param json the mapper
   * @param row the archive row
   * @param jsonColumns which of its columns hold JSON as a string
   * @return the row as JSON text, with those columns unwrapped
   */
  public static String asJson(
      tools.jackson.databind.ObjectMapper json,
      java.util.Map<String, Object> row,
      java.util.Set<String> jsonColumns) {
    java.util.Map<String, Object> converted = new java.util.LinkedHashMap<>(row);
    for (String column : jsonColumns) {
      if (converted.get(column) instanceof String text && !text.isBlank()) {
        converted.put(column, json.readTree(text));
      }
    }
    return json.writeValueAsString(converted);
  }

  /**
   * One archive value as a UUID.
   *
   * <p>The archive holds an id as a string, because JSON has no uuid. Every reference an importer
   * resolves goes through here rather than through {@code toString} at the call site, so a value
   * that is not an id fails where it is read instead of three statements later.
   *
   * @param value what the archive held, a string or null
   * @return the id, or null
   */
  public static java.util.UUID uuid(Object value) {
    return value == null ? null : java.util.UUID.fromString(value.toString());
  }

  /**
   * One archive value as text.
   *
   * @param value what the archive held
   * @return its text, or null
   */
  public static String text(Object value) {
    return value == null ? null : value.toString();
  }

  /**
   * One archive value as a whole number.
   *
   * @param value what the archive held
   * @return the number, or null
   */
  public static Integer number(Object value) {
    return value == null ? null : Integer.valueOf(value.toString());
  }

  /**
   * The column list as expressions reading from the populated record.
   *
   * @param columns the columns
   * @return {@code (r).a, (r).b, …}
   */
  private static String values(List<String> columns) {
    return columns.stream().map(column -> "(r)." + column).collect(Collectors.joining(", "));
  }
}
