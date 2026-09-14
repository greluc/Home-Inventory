/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.catalog.infrastructure;

import de.greluc.homeinv.platform.Page;
import de.greluc.homeinv.catalog.api.LocationCategories;
import de.greluc.homeinv.catalog.api.LocationCategoryView;
import de.greluc.homeinv.platform.CursorCodec;
import de.greluc.homeinv.platform.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads the location categories a tenant may put a place into — the shipped ones and its own.
 *
 * <p>{@link JdbcClient} rather than a JPA entity, for the reason
 * {@link CatalogProvisioningAdapter} gives: these rows are edited by exactly one caller, the
 * statements are flat, and an aggregate per row would be structure written for nobody. Editing them
 * is {@link de.greluc.homeinv.catalog.api.TypeAdministration}'s, not this port's — this one answers
 * the picker.
 *
 * <p>Row-level security does the tenant scoping, as everywhere else — the query still names
 * {@code tenant_id} so that a missing context is a failure here rather than a silent empty list one
 * layer up.
 */
@Component
@RequiredArgsConstructor
public class LocationCategoryQueries implements LocationCategories {

  /** The cap REQ-NFR-010 puts on every page of every collection. */
  private static final int MAX_PAGE = 200;

  /** What a category cursor is bound to. There is one listing, so it takes no filter. */
  private static final String CURSOR_FINGERPRINT = "location-categories";

  private final JdbcClient jdbc;
  private final CursorCodec cursors;
  private final ObjectMapper mapper;

  @Override
  @Transactional(readOnly = true)
  public Page<LocationCategoryView> list(String cursor, int limit) {
    UUID tenantId = TenantContext.require();
    int size = Math.clamp(limit, 1, MAX_PAGE);

    List<Row> rows;
    if (cursor == null || cursor.isBlank()) {
      rows =
          jdbc.sql(
                  """
                  select id, key, labels, icon, is_mobile, created_at
                  from catalog.location_category
                  where tenant_id = ? and archived_at is null
                  order by created_at asc, id asc
                  limit ?
                  """)
              .params(tenantId, size)
              .query(Row.class)
              .list();
    } else {
      // Throws when the cursor was tampered with or belongs to another listing.
      CursorCodec.Position after = cursors.decode(cursor, CURSOR_FINGERPRINT);
      // Wrapped, not an Instant: the driver cannot infer a SQL type for one.
      java.sql.Timestamp at = java.sql.Timestamp.from(after.createdAt());
      rows =
          jdbc.sql(
                  """
                  select id, key, labels, icon, is_mobile, created_at
                  from catalog.location_category
                  where tenant_id = ? and archived_at is null
                    and (created_at > ? or (created_at = ? and id > ?))
                  order by created_at asc, id asc
                  limit ?
                  """)
              .params(tenantId, at, at, after.id(), size)
              .query(Row.class)
              .list();
    }

    List<LocationCategoryView> views =
        rows.stream()
            .map(
                row ->
                    new LocationCategoryView(
                        row.id(), row.key(), labels(row.labels()), row.icon(), row.isMobile()))
            .toList();

    String nextCursor = null;
    if (rows.size() == size) {
      Row last = rows.get(rows.size() - 1);
      nextCursor =
          cursors.encode(CursorCodec.Position.of(last.createdAt(), last.id()), CURSOR_FINGERPRINT);
    }
    return Page.of(views, nextCursor);
  }

  /**
   * The tenant's own name for a category, as JSON in the row.
   *
   * <p>Parsed here rather than mapped by the driver: the column is {@code jsonb} and the view wants
   * a map, and a category that has never been named carries {@code {}} rather than null.
   *
   * @param json the column, which is never null
   * @return the labels per language tag, empty when there are none
   */
  private Map<String, String> labels(String json) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    return mapper.readValue(json, new TypeReference<Map<String, String>>() {});
  }

  /**
   * One row as the query returns it.
   *
   * @param id the category
   * @param key the shipped key
   * @param labels the tenant's own name per language tag, as JSON text
   * @param icon an icon name for the client, or null
   * @param isMobile whether its locations travel with their contents
   * @param createdAt the position half of the keyset cursor
   */
  public record Row(
      UUID id, String key, String labels, String icon, boolean isMobile, Instant createdAt) {}
}
