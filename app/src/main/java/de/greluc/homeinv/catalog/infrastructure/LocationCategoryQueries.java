/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.catalog.infrastructure;

import de.greluc.homeinv.catalog.api.LocationCategories;
import de.greluc.homeinv.catalog.api.LocationCategoryView;
import de.greluc.homeinv.platform.CursorCodec;
import de.greluc.homeinv.platform.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the shipped location categories.
 *
 * <p>{@link JdbcClient} rather than a JPA entity, for the reason
 * {@link CatalogProvisioningAdapter} gives: the catalog tables are read-only at stage 0, and an
 * aggregate for a table nothing mutates would be structure written for a stage that has not arrived.
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

  @Override
  @Transactional(readOnly = true)
  public LocationCategoryPage list(String cursor, int limit) {
    UUID tenantId = TenantContext.require();
    int size = Math.clamp(limit, 1, MAX_PAGE);

    List<Row> rows;
    if (cursor == null || cursor.isBlank()) {
      rows =
          jdbc.sql(
                  """
                  select id, key, is_mobile, created_at
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
      rows =
          jdbc.sql(
                  """
                  select id, key, is_mobile, created_at
                  from catalog.location_category
                  where tenant_id = ? and archived_at is null
                    and (created_at > ? or (created_at = ? and id > ?))
                  order by created_at asc, id asc
                  limit ?
                  """)
              .params(tenantId, after.createdAt(), after.createdAt(), after.id(), size)
              .query(Row.class)
              .list();
    }

    List<LocationCategoryView> views =
        rows.stream()
            .map(row -> new LocationCategoryView(row.id(), row.key(), row.isMobile()))
            .toList();

    String nextCursor = null;
    if (rows.size() == size) {
      Row last = rows.get(rows.size() - 1);
      nextCursor =
          cursors.encode(new CursorCodec.Position(last.createdAt(), last.id()), CURSOR_FINGERPRINT);
    }
    return new LocationCategoryPage(views, nextCursor);
  }

  /**
   * One row as the query returns it.
   *
   * @param id the category
   * @param key the shipped key
   * @param isMobile whether its locations travel with their contents
   * @param createdAt the position half of the keyset cursor
   */
  public record Row(UUID id, String key, boolean isMobile, Instant createdAt) {}
}
