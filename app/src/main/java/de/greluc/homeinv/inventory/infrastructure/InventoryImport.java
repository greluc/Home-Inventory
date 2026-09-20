/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.infrastructure;

import de.greluc.homeinv.catalog.api.AttributeSealing;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.portability.api.ImportSql;
import de.greluc.homeinv.portability.api.ImportTarget;
import de.greluc.homeinv.portability.api.Remapping;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads the things back out of an archive (REQ-PORT-003).
 *
 * <p>Items first, then everything that hangs off one: maintenance entries, loans, relations and
 * bundles. The last two point at <b>two</b> items each, which is the reason they are written after
 * every item rather than beside them.
 *
 * <h2>Sealed again on the way in</h2>
 *
 * <p>An export opens a sensitive value as far as its requester may read it (ADR-0068), so the
 * archive holds plaintext where the column holds ciphertext. It is sealed again here, under the
 * receiving tenant's own key — writing it back unchanged would leave a value somebody marked
 * sensitive sitting in the column in clear.
 *
 * <h2>The search vectors are not written and cannot be</h2>
 *
 * <p>{@code search_vector_de} and {@code search_vector_en} are generated columns. The archive does
 * not carry them (they are one fact twice) and PostgreSQL refuses an insert into one, which is why
 * the column list here is written out rather than taken from the row.
 */
@Component
@RequiredArgsConstructor
public class InventoryImport implements ImportTarget {

  private static final List<String> ITEM =
      List.of(
          "id", "item_type_version_id", "name", "description", "kind", "location_id", "quantity",
          "quantity_unit", "lifecycle_state", "attributes", "notes", "minimum_stock",
          "purchase_amount", "purchase_currency", "purchased_on", "purchase_source",
          "warranty_until", "lifetime_warranty",
          "replacement_amount", "replacement_currency", "replacement_as_of", "replacement_source",
          "current_amount", "current_currency", "current_as_of",
          "disposal_amount", "disposal_currency", "disposed_on", "disposal_recipient",
          "disposal_note", "maintenance_interval_days",
          "created_at", "updated_at", "created_by", "updated_by", "deleted_at", "version");

  private static final List<String> MAINTENANCE =
      List.of(
          "id", "item_id", "performed_on", "kind", "cost_amount", "cost_currency", "note",
          "created_at", "created_by");

  private static final List<String> LOAN =
      List.of(
          "id", "item_id", "borrower_user_id", "borrower_name", "handed_out_on", "due_on",
          "returned_on", "note", "created_at", "created_by", "updated_at", "updated_by", "version");

  private static final List<String> RELATION =
      List.of("id", "source_id", "target_id", "relation_type", "created_at", "created_by",
          "version");

  private static final List<String> BUNDLE =
      List.of("id", "bundle_item_id", "member_item_id", "created_at", "created_by", "version");

  /** Columns an export wrote through {@code ::text} and an import has to unwrap again. */
  private static final Set<String> JSON_COLUMNS = Set.of("attributes");

  private final JdbcClient jdbc;
  private final ObjectMapper json;
  private final AttributeSealing sealing;

  @Override
  public String block() {
    return "inventory";
  }

  @Override
  public int order() {
    return 30;
  }

  @Override
  public Outcome importFrom(Archive archive, Remapping ids) {
    Outcome outcome = items(archive, ids);
    outcome = outcome.plus(plain(archive, "maintenance-entries", "inventory.maintenance_entry",
        MAINTENANCE));
    outcome = outcome.plus(plain(archive, "loans", "inventory.loan", LOAN));
    outcome = outcome.plus(plain(archive, "relations", "inventory.item_relation", RELATION));
    outcome = outcome.plus(plain(archive, "bundles", "inventory.item_bundle", BUNDLE));
    return outcome;
  }

  /**
   * The items themselves, with their attributes sealed again.
   *
   * <p>A borrower's id is deliberately <b>not</b> remapped, and cannot be: an import writes no
   * accounts, so an id in {@code loan.borrower_user_id} names somebody who is not here. The column
   * has no foreign key for exactly this reason, and a loan to a person the receiving instance does
   * not know is still a true record of who had the thing.
   *
   * @param archive the archive
   * @param ids the remapping
   * @return what happened
   */
  private Outcome items(Archive archive, Remapping ids) {
    int inserted = 0;
    int overwritten = 0;
    for (Map<String, Object> row : archive.rows("inventory", "items")) {
      Map<String, Object> resolved = new LinkedHashMap<>(row);
      UUID typeVersion = ids.resolve(ImportSql.uuid(row.get("item_type_version_id")));
      resolved.put("item_type_version_id", typeVersion);
      resolved.put(
          "attributes",
          sealing.sealed(
              typeVersion,
              ImportSql.uuid(row.get("id")),
              ImportSql.text(row.get("attributes"))));

      if (write("inventory.item", ITEM, resolved)) {
        inserted++;
      } else {
        overwritten++;
      }
    }
    return new Outcome(inserted, overwritten, 0);
  }

  /**
   * One dataset whose rows need nothing resolved.
   *
   * @param archive the archive
   * @param dataset its name in the archive
   * @param table where it goes
   * @param columns its columns
   * @return what happened
   */
  private Outcome plain(Archive archive, String dataset, String table, List<String> columns) {
    int inserted = 0;
    int overwritten = 0;
    for (Map<String, Object> row : archive.rows("inventory", dataset)) {
      if (write(table, columns, row)) {
        inserted++;
      } else {
        overwritten++;
      }
    }
    return new Outcome(inserted, overwritten, 0);
  }

  /**
   * Writes one row, saying whether it was new.
   *
   * @param table where it goes
   * @param columns its columns
   * @param row the row
   * @return whether it was inserted rather than overwritten
   */
  private boolean write(String table, List<String> columns, Map<String, Object> row) {
    return Boolean.TRUE.equals(
        jdbc.sql(ImportSql.upsert(table, columns, "tenant_id, id"))
            .param(TenantContext.require())
            .param(ImportSql.asJson(json, row, JSON_COLUMNS))
            .query(Boolean.class)
            .single());
  }
}
