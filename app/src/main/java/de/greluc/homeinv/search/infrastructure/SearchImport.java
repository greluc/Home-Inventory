/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.search.infrastructure;

import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.portability.api.ImportSql;
import de.greluc.homeinv.portability.api.ImportTarget;
import de.greluc.homeinv.portability.api.Remapping;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads saved searches back out of an archive (REQ-PORT-003, REQ-PORT-006).
 *
 * <p>{@code filters} is a {@code text[]} and the archive carries it as a JSON array, which {@code
 * jsonb_populate_record} turns back into an array because that is what the column is. The index
 * itself is neither exported nor imported: OpenSearch is derived, and it is rebuilt from the rows
 * that did arrive.
 */
@Component
@RequiredArgsConstructor
public class SearchImport implements ImportTarget {

  private static final List<String> SAVED_SEARCH =
      List.of(
          "id", "name", "query_text", "filters", "sort",
          "created_at", "updated_at", "created_by", "updated_by", "version");

  private final JdbcClient jdbc;
  private final ObjectMapper json;

  @Override
  public String block() {
    return "search";
  }

  @Override
  public int order() {
    return 50;
  }

  @Override
  public Outcome importFrom(Archive archive, Remapping ids) {
    int inserted = 0;
    int overwritten = 0;
    for (Map<String, Object> row : archive.rows("search", "saved-searches")) {
      boolean fresh =
          Boolean.TRUE.equals(
              jdbc.sql(ImportSql.upsert("search.saved_search", SAVED_SEARCH, "tenant_id, id"))
                  .param(TenantContext.require())
                  .param(ImportSql.asJson(json, row, Set.of()))
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
