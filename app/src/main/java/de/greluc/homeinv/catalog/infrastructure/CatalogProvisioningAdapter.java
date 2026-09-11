/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.catalog.infrastructure;

import de.greluc.homeinv.catalog.api.CatalogProvisioning;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Writes the rows a new tenant needs in order to be usable at all.
 *
 * <p>Written with {@link JdbcClient} rather than JPA entities, and that is a deliberate reading of
 * ADR-0017 rather than a shortcut. The catalog tables are **read-only at stage 0**: the type system
 * that edits them is stage 1 (REQ-CORE-020…032). Three JPA aggregates for tables nothing mutates
 * would be speculative structure, and the seeding is a handful of fixed inserts, which is exactly
 * the hand-written-SQL half of that ADR.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class CatalogProvisioningAdapter implements CatalogProvisioning {

  /** The key of the type every stage-0 item references. */
  private static final String BUILTIN_TYPE_KEY = "general";

  /**
   * The categories from REQ-CORE-042, in the order a tree is usually built.
   *
   * <p>That requirement reads stage 1 because its acceptance is "all present **and editable**", and
   * editing them is the stage-1 half. They are seeded now because
   * {@code locations.location.category_id} is {@code NOT NULL} and stage 0 needs a location tree —
   * so the choice was between this list and a different, invented one. Seeding the specified list
   * means stage 1 only has to make them editable, with no data to migrate.
   *
   * <p>{@code is_mobile} is false for every one of them, including vehicle and moving box: mobile
   * locations are stage 2 (REQ-CORE-041), and a category that claims to be mobile before anything
   * honours the claim would be a lie in the data.
   */
  private static final List<String> SHIPPED_CATEGORIES =
      List.of(
          "building",
          "floor",
          "room",
          "furniture",
          "shelf",
          "compartment",
          "drawer",
          "box",
          "moving-box",
          "vehicle",
          "warehouse",
          "outdoor-storage",
          "locker");

  private final JdbcClient jdbc;

  @Override
  public UUID provisionDefaults(UUID tenantId, UUID actor) {
    UUID typeId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();

    jdbc.sql(
            """
            insert into catalog.item_type (id, tenant_id, key, kind, builtin, created_by, updated_by)
            values (?, ?, ?, 'PHYSICAL', true, ?, ?)
            """)
        .params(typeId, tenantId, BUILTIN_TYPE_KEY, actor, actor)
        .update();

    // An empty JSON Schema, because the type defines no fields: stage 0 has the
    // five fixed columns and nothing else (REQ-CORE-002). Stage 1 generates a
    // real schema per version (REQ-CORE-027) and this row is simply version 1.
    jdbc.sql(
            """
            insert into catalog.item_type_version
                (id, tenant_id, item_type_id, version_number, json_schema, published_at, created_by)
            values (?, ?, ?, 1, '{}'::jsonb, now(), ?)
            """)
        .params(versionId, tenantId, typeId, actor)
        .update();

    for (String key : SHIPPED_CATEGORIES) {
      jdbc.sql(
              """
              insert into catalog.location_category
                  (id, tenant_id, key, labels, builtin, is_mobile, created_by, updated_by)
              values (?, ?, ?, '{}'::jsonb, true, false, ?, ?)
              """)
          .params(UUID.randomUUID(), tenantId, key, actor, actor)
          .update();
    }

    log.info(
        "Provisioned tenant {}: built-in type {} and {} location categories",
        tenantId,
        BUILTIN_TYPE_KEY,
        SHIPPED_CATEGORIES.size());
    return versionId;
  }

  @Override
  public UUID builtinItemTypeVersion(UUID tenantId) {
    return jdbc
        .sql(
            """
            select v.id
            from catalog.item_type_version v
            join catalog.item_type t on t.id = v.item_type_id and t.tenant_id = v.tenant_id
            where t.tenant_id = ? and t.builtin = true and t.key = ?
            order by v.version_number desc
            limit 1
            """)
        .params(tenantId, BUILTIN_TYPE_KEY)
        .query(UUID.class)
        .optional()
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "Tenant "
                        + tenantId
                        + " has no built-in item type. Provisioning did not complete, and no item "
                        + "can be created until it does."));
  }
}
