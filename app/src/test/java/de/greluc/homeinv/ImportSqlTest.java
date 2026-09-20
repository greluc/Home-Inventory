/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.homeinv.portability.api.ImportSql;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

/**
 * The one builder allowed to concatenate SQL, and what makes it allowed (REQ-SEC-031).
 *
 * <p>{@code ArchitectureRulesTest.sqlIsNeverConcatenated} exempts {@code ImportSql} by name, on the
 * strength of one sentence: every identifier it emits is checked first. That sentence is a claim
 * until something tries to get a quote past it, which is what this does.
 */
@DisplayName("The import statement builder")
class ImportSqlTest {

  private static final List<String> COLUMNS = List.of("id", "name", "created_at");

  @Test
  @DisplayName("names every column twice, once to read and once to overwrite")
  void anUpsertReadsAndAssigns() {
    String sql = ImportSql.upsert("inventory.item", COLUMNS, "tenant_id, id");

    assertThat(sql).contains("insert into inventory.item (tenant_id, id, name, created_at)");
    assertThat(sql).contains("select ?::uuid, (r).id, (r).name, (r).created_at");
    assertThat(sql).contains("jsonb_populate_record(null::inventory.item, ?::jsonb)");
    assertThat(sql).contains("on conflict (tenant_id, id) do update set");

    // Every column is assigned except `id`, which is what the row is matched on:
    // assigning it would be writing the value it was found by.
    assertThat(sql).contains("name = excluded.name").contains("created_at = excluded.created_at");
    assertThat(sql).doesNotContain("id = excluded.id");

    // And it says which branch fired, which is how the report tells an insert
    // from an overwrite without a second query per row.
    assertThat(sql).contains("returning (xmax = 0) as inserted");
  }

  @Test
  @DisplayName("never assigns the id when it updates one row by it")
  void anUpdateKeepsTheLocalId() {
    String sql = ImportSql.update("catalog.item_type", COLUMNS);

    assertThat(sql).startsWith("update catalog.item_type set ");
    assertThat(sql).contains("name = (r).name").contains("created_at = (r).created_at");
    assertThat(sql).doesNotContain("id = (r).id");
    assertThat(sql).endsWith("where catalog.item_type.id = ?::uuid");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "item; drop table inventory.item",
        "item\" ",
        "inventory.item.extra",
        ".item",
        "item.",
        "Item",
        "1item",
        "item-name",
        "item name",
        ""
      })
  @DisplayName("refuses anything that is not a plain identifier, before building a statement")
  void aNameThatIsNotOneIsRefused(String name) {
    assertThatThrownBy(() -> ImportSql.upsert(name, COLUMNS, "tenant_id, id"))
        .as("a table name that is not an identifier never reaches a statement")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ImportSql.insert("inventory.item", List.of("id", name)))
        .as("nor does a column name")
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("unwraps the JSON an export wrote as a string, and leaves everything else alone")
  void jsonColumnsComeBackAsObjects() {
    ObjectMapper json = new ObjectMapper();
    Map<String, Object> row =
        Map.of("id", "0198c0de-0000-7000-8000-000000000000", "labels", "{\"en\":\"Box\"}");

    // Without unwrapping, the archive's string would be stored as a JSON string
    // -- itself a valid `jsonb` value, so nothing would fail and the labels
    // would be a quoted blob for ever.
    assertThat(ImportSql.asJson(json, row, Set.of("labels")))
        .contains("\"labels\":{\"en\":\"Box\"}");
    assertThat(ImportSql.asJson(json, row, Set.of()))
        .contains("\"labels\":\"{\\\"en\\\":\\\"Box\\\"}\"");
  }
}
