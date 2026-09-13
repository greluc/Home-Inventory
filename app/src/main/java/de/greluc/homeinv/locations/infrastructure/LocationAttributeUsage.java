/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.locations.infrastructure;

import de.greluc.homeinv.catalog.api.AttributeUsage;
import de.greluc.homeinv.platform.TenantContext;
import java.util.Collection;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * What {@code locations} can say about the values stored under one field key.
 *
 * <p>The same answer {@code ItemAttributeUsage} gives for items, over the tree. A category's fields
 * are as removable as a type's, and a removal that reached only one of the two would leave the other
 * holding values under a key nothing declares.
 *
 * <p>Locations have no side table: {@code item_attr_index} mirrors items only, because the filters
 * REQ-CORE-013 calls transactionally exact are item filters. Stripping a location is therefore one
 * statement.
 */
@Component
@RequiredArgsConstructor
public class LocationAttributeUsage implements AttributeUsage {

  private final JdbcClient jdbc;

  @Override
  @Transactional(readOnly = true)
  public long countCarrying(String fieldKey, Collection<UUID> typeVersionIds) {
    if (typeVersionIds.isEmpty()) {
      return 0;
    }
    return jdbc
        .sql(
            """
            select count(*) from locations.location
            where tenant_id = ?
              and category_version_id = any (?::uuid[])
              and jsonb_exists(attributes, ?)
            """)
        .params(TenantContext.require(), array(typeVersionIds), fieldKey)
        .query(Long.class)
        .single();
  }

  @Override
  @Transactional
  public long strip(String fieldKey, Collection<UUID> typeVersionIds) {
    if (typeVersionIds.isEmpty()) {
      return 0;
    }
    return jdbc.sql(
            """
            update locations.location
            set attributes = attributes - ?, updated_at = now(), version = version + 1
            where tenant_id = ?
              and category_version_id = any (?::uuid[])
              and jsonb_exists(attributes, ?)
            """)
        .params(fieldKey, TenantContext.require(), array(typeVersionIds), fieldKey)
        .update();
  }

  /**
   * A PostgreSQL array literal of uuids.
   *
   * @param ids the versions
   * @return {@code {a,b,c}}, which the query casts
   */
  private String array(Collection<UUID> ids) {
    return ids.stream().map(UUID::toString).distinct().collect(Collectors.joining(",", "{", "}"));
  }
}
