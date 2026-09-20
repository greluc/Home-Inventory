/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tagging.infrastructure;

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
 * Reads tags back out of an archive (REQ-PORT-003).
 *
 * <p>Groups, then tags, then what they are on. Assignments come last because each points at a tag
 * and at an item or a place, and both of those have to be there first — which is why this block
 * runs at {@link #order()} 40, after {@code inventory} and {@code locations}.
 *
 * <p>{@code merged_into} is written in a second pass. A merged tag keeps its own name and points at
 * the one it became (REQ-CORE-063), and the archive is ordered by name rather than by that chain,
 * so a single pass would point at a tag that has not arrived yet.
 */
@Component
@RequiredArgsConstructor
public class TaggingImport implements ImportTarget {

  private static final List<String> GROUP =
      List.of(
          "id", "key", "labels", "exclusive", "display_order",
          "created_at", "updated_at", "created_by", "updated_by", "version");

  private static final List<String> TAG =
      List.of(
          "id", "name", "tag_group_id", "colour", "icon", "merged_into",
          "created_at", "updated_at", "created_by", "updated_by", "version");

  private static final List<String> ASSIGNMENT =
      List.of(
          "id", "tag_id", "item_id", "location_id",
          "created_at", "updated_at", "created_by", "updated_by", "version");

  /** Columns an export wrote through {@code ::text} and an import has to unwrap again. */
  private static final Set<String> JSON_COLUMNS = Set.of("labels");

  private final JdbcClient jdbc;
  private final ObjectMapper json;

  @Override
  public String block() {
    return "tagging";
  }

  @Override
  public int order() {
    return 40;
  }

  @Override
  public Outcome importFrom(Archive archive, Remapping ids) {
    Outcome outcome = write(archive, "tag-groups", "tagging.tag_group", GROUP, null);
    outcome = outcome.plus(write(archive, "tags", "tagging.tag", TAG, "merged_into"));
    outcome =
        outcome.plus(write(archive, "tag-assignments", "tagging.tag_assignment", ASSIGNMENT, null));

    for (Map<String, Object> row : archive.rows("tagging", "tags")) {
      UUID merged = ImportSql.uuid(row.get("merged_into"));
      if (merged != null) {
        jdbc.sql("update tagging.tag set merged_into = ? where id = ?")
            .param(merged)
            .param(ImportSql.uuid(row.get("id")))
            .update();
      }
    }
    return outcome;
  }

  /**
   * One dataset, upserted by id.
   *
   * @param archive the archive
   * @param dataset its name in the archive
   * @param table where it goes
   * @param columns its columns
   * @param deferred a self-reference written in a later pass, or null
   * @return what happened
   */
  private Outcome write(
      Archive archive, String dataset, String table, List<String> columns, String deferred) {
    int inserted = 0;
    int overwritten = 0;
    for (Map<String, Object> row : archive.rows("tagging", dataset)) {
      Map<String, Object> resolved = new LinkedHashMap<>(row);
      if (deferred != null) {
        resolved.put(deferred, null);
      }
      boolean fresh =
          Boolean.TRUE.equals(
              jdbc.sql(ImportSql.upsert(table, columns, "tenant_id, id"))
                  .param(TenantContext.require())
                  .param(ImportSql.asJson(json, resolved, JSON_COLUMNS))
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
}
