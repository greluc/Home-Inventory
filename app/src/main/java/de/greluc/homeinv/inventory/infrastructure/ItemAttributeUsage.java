/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.infrastructure;

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
 * What {@code inventory} can say about the values stored under one field key.
 *
 * <p>The half of REQ-CORE-026 that lives where the rows do. {@code catalog} owns the field
 * definition and may not read this schema (04 §4.5); it declares the question in
 * {@link AttributeUsage} and this answers it.
 *
 * <p>{@code jsonb_exists} rather than the {@code ?} operator, because a {@code ?} in a prepared
 * statement is a placeholder and the driver would bind to it.
 */
@Component
@RequiredArgsConstructor
public class ItemAttributeUsage implements AttributeUsage {

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
            select count(*) from inventory.item
            where tenant_id = ?
              and item_type_version_id = any (?::uuid[])
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
    UUID tenantId = TenantContext.require();
    String versions = array(typeVersionIds);

    // The side table first: its rows hang off the item, and once the key is gone
    // from `attributes` nothing would say which of them to remove.
    jdbc.sql(
            """
            delete from inventory.item_attr_index
            where tenant_id = ? and field_key = ?
              and item_id in (select id from inventory.item
                              where tenant_id = ? and item_type_version_id = any (?::uuid[]))
            """)
        .params(tenantId, fieldKey, tenantId, versions)
        .update();

    return jdbc.sql(
            """
            update inventory.item
            set attributes = attributes - ?, updated_at = now(), version = version + 1
            where tenant_id = ?
              and item_type_version_id = any (?::uuid[])
              and jsonb_exists(attributes, ?)
            """)
        .params(fieldKey, tenantId, versions, fieldKey)
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
