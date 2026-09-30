/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.locations.infrastructure;

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
 * Reads the place tree back out of an archive (REQ-PORT-003).
 *
 * <h2>Order is already in the file</h2>
 *
 * <p>A place inside a place needs its parent to exist first, and the export writes the tree ordered
 * by {@code path} — which for an {@code ltree} means {@code shed} before {@code shed.shelf} before
 * {@code shed.shelf.box}. So a single pass in the archive's own order satisfies the self-reference
 * without sorting anything here. {@code catalog} has already run, so the category version each
 * place is written against is resolvable.
 *
 * <h2>Sealed again on the way in</h2>
 *
 * <p>An export <b>opens</b> a sensitive value as far as its requester may read it (ADR-0068), so
 * the archive holds plaintext where the column holds ciphertext. Writing it back unchanged would
 * put a value somebody marked sensitive into the column in clear, on an instance whose operator
 * never agreed to that. It is sealed again here, under the receiving tenant's own key.
 */
@Component
@RequiredArgsConstructor
public class LocationImport implements ImportTarget {

  private static final List<String> LOCATION =
      List.of(
          "id", "category_version_id", "parent_id", "name", "path", "depth", "is_mobile",
          "attributes", "sealed_at", "created_at", "updated_at", "created_by", "updated_by",
          "deleted_at", "version");

  /** Columns an export wrote through {@code ::text} and an import has to unwrap again. */
  private static final Set<String> JSON_COLUMNS = Set.of("attributes");

  private final JdbcClient jdbc;
  private final ObjectMapper json;
  private final AttributeSealing sealing;

  @Override
  public String block() {
    return "locations";
  }

  @Override
  public int order() {
    return 20;
  }

  @Override
  public Outcome importFrom(Archive archive, Remapping ids) {
    int inserted = 0;
    int overwritten = 0;
    for (Map<String, Object> row : archive.rows("locations", "locations")) {
      Map<String, Object> resolved = new LinkedHashMap<>(row);
      UUID categoryVersion = ids.resolve(ImportSql.uuid(row.get("category_version_id")));
      resolved.put("category_version_id", categoryVersion);
      resolved.put(
          "attributes",
          sealing.sealed(
              categoryVersion,
              ImportSql.uuid(row.get("id")),
              row.get("attributes") == null ? null : row.get("attributes").toString()));

      boolean fresh =
          Boolean.TRUE.equals(
              jdbc.sql(ImportSql.upsert("locations.location", LOCATION, "tenant_id, id"))
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
