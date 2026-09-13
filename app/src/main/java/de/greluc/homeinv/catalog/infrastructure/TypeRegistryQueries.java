/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.catalog.infrastructure;

import de.greluc.homeinv.catalog.api.FieldConstraints;
import de.greluc.homeinv.catalog.api.FieldDataType;
import de.greluc.homeinv.catalog.api.FieldDefinitionView;
import de.greluc.homeinv.catalog.api.TypeRegistry;
import de.greluc.homeinv.catalog.api.VisibilityRule;
import de.greluc.homeinv.platform.TenantContext;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads the type system out of the {@code catalog} schema.
 *
 * <p>{@link JdbcClient} rather than JPA, for the reason the provisioning adapter gives and one more:
 * every read here is a projection into a view record, and an entity would be a writable thing handed
 * to callers who must not write (REQ-NFR-022).
 *
 * <p>Row-level security scopes every statement; the queries still name {@code tenant_id} so that a
 * missing context fails here rather than returning an empty answer that reads like "no such type".
 */
@Component
@RequiredArgsConstructor
public class TypeRegistryQueries implements TypeRegistry {

  private final JdbcClient jdbc;
  private final ObjectMapper mapper;

  @Override
  @Transactional(readOnly = true)
  public UUID publishedItemTypeVersion(UUID itemTypeId) {
    UUID tenantId = TenantContext.require();
    return jdbc
        .sql(
            """
            select v.id
            from catalog.item_type_version v
            join catalog.item_type t
              on t.tenant_id = v.tenant_id and t.id = v.item_type_id
            where v.tenant_id = ?
              and t.id = ?
              and t.archived_at is null
              and v.published_at is not null
            order by v.version_number desc
            limit 1
            """)
        .params(tenantId, itemTypeId)
        .query(UUID.class)
        .optional()
        .orElseThrow(
            () ->
                new UnknownTypeException(
                    "Item type " + itemTypeId + " has no published version for this tenant."));
  }

  @Override
  @Transactional(readOnly = true)
  public UUID publishedCategoryVersion(UUID categoryId) {
    UUID tenantId = TenantContext.require();
    return jdbc
        .sql(
            """
            select v.id
            from catalog.location_category_version v
            join catalog.location_category c
              on c.tenant_id = v.tenant_id and c.id = v.location_category_id
            where v.tenant_id = ?
              and c.id = ?
              and c.archived_at is null
              and v.published_at is not null
            order by v.version_number desc
            limit 1
            """)
        .params(tenantId, categoryId)
        .query(UUID.class)
        .optional()
        .orElseThrow(
            () ->
                new UnknownTypeException(
                    "Location category " + categoryId + " has no published version for this tenant."));
  }

  @Override
  @Transactional(readOnly = true)
  public Map<UUID, UUID> categoriesOfVersions(Collection<UUID> categoryVersionIds) {
    return ownersOf(
        categoryVersionIds,
        """
        select id, location_category_id as owner_id
        from catalog.location_category_version
        where tenant_id = ? and id = any (?::uuid[])
        """);
  }

  @Override
  @Transactional(readOnly = true)
  public Map<UUID, UUID> itemTypesOfVersions(Collection<UUID> itemTypeVersionIds) {
    return ownersOf(
        itemTypeVersionIds,
        """
        select id, item_type_id as owner_id
        from catalog.item_type_version
        where tenant_id = ? and id = any (?::uuid[])
        """);
  }

  /**
   * Resolves a batch of version ids to whatever owns them.
   *
   * <p>One statement with a PostgreSQL array rather than an {@code IN} list assembled from
   * placeholders: a listing carries up to two hundred ids, and an assembled list is string-built SQL,
   * which {@code ArchitectureRulesTest} refuses on sight and is right to.
   *
   * @param versionIds the versions, duplicates and nulls tolerated
   * @param sql a query selecting {@code id} and {@code owner_id}
   * @return version id to owner id
   */
  private Map<UUID, UUID> ownersOf(Collection<UUID> versionIds, String sql) {
    if (versionIds == null || versionIds.isEmpty()) {
      return Map.of();
    }
    UUID tenantId = TenantContext.require();
    Map<UUID, UUID> owners = new LinkedHashMap<>();
    jdbc.sql(sql)
        .params(tenantId, arrayLiteral(versionIds))
        .query(
            (rs, rowNum) -> {
              owners.put(rs.getObject("id", UUID.class), rs.getObject("owner_id", UUID.class));
              return null;
            })
        .list();
    return owners;
  }

  @Override
  @Transactional(readOnly = true)
  public List<FieldDefinitionView> fields(UUID typeVersionId) {
    UUID tenantId = TenantContext.require();
    return jdbc
        .sql(
            """
            select id, key, data_type, labels, help_texts, required, default_value,
                   constraints, value_list_id, visibility, field_group, display_order,
                   searchable, sortable, facetable, sensitive, deprecated_at
            from catalog.field_definition
            where tenant_id = ?
              and (item_type_version_id = ? or location_category_version_id = ?)
            order by coalesce(field_group, ''), display_order, key
            """)
        .params(tenantId, typeVersionId, typeVersionId)
        .query((rs, rowNum) -> toView(rs))
        .list();
  }

  @Override
  @Transactional(readOnly = true)
  public String jsonSchema(UUID typeVersionId) {
    UUID tenantId = TenantContext.require();
    return jdbc
        .sql(
            """
            select json_schema::text from catalog.item_type_version
            where tenant_id = ? and id = ?
            union all
            select json_schema::text from catalog.location_category_version
            where tenant_id = ? and id = ?
            """)
        .params(tenantId, typeVersionId, tenantId, typeVersionId)
        .query(String.class)
        .optional()
        .orElseThrow(
            () ->
                new UnknownTypeException(
                    "No type version " + typeVersionId + " is visible to this tenant."));
  }

  @Override
  @Transactional(readOnly = true)
  public List<String> valueListEntries(UUID valueListId) {
    UUID tenantId = TenantContext.require();
    return jdbc
        .sql(
            """
            select value
            from catalog.value_list_entry
            where tenant_id = ? and value_list_id = ? and archived_at is null
            order by display_order, value
            """)
        .params(tenantId, valueListId)
        .query(String.class)
        .list();
  }

  /**
   * One row as a view.
   *
   * @param rs the row
   * @return the field as everything outside this block sees it
   * @throws java.sql.SQLException when the row cannot be read
   */
  private FieldDefinitionView toView(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new FieldDefinitionView(
        rs.getObject("id", UUID.class),
        rs.getString("key"),
        FieldDataType.ofToken(rs.getString("data_type")),
        strings(rs.getString("labels")),
        strings(rs.getString("help_texts")),
        rs.getBoolean("required"),
        rs.getString("default_value"),
        constraints(rs.getString("constraints")),
        rs.getObject("value_list_id", UUID.class),
        visibility(rs.getString("visibility")),
        rs.getString("field_group"),
        rs.getInt("display_order"),
        rs.getBoolean("searchable"),
        rs.getBoolean("sortable"),
        rs.getBoolean("facetable"),
        rs.getBoolean("sensitive"),
        rs.getTimestamp("deprecated_at") != null);
  }

  /**
   * A JSONB object of strings as a map, for labels and help texts.
   *
   * @param json the column value, possibly {@code null}
   * @return the map, empty when the column held nothing or an empty object
   */
  private Map<String, String> strings(String json) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    JsonNode node = mapper.readTree(json);
    Map<String, String> values = new LinkedHashMap<>();
    node.properties().forEach(entry -> values.put(entry.getKey(), entry.getValue().asString()));
    return Map.copyOf(values);
  }

  /**
   * The {@code constraints} column as a record.
   *
   * <p>An unknown member is ignored rather than refused. The column is written by this application
   * and validated on the way in; a reader that threw on a member a later version added would turn a
   * forward-compatible write into an outage on the old instance during a rolling update.
   *
   * @param json the column value, possibly {@code null}
   * @return the constraints, {@link FieldConstraints#NONE} when there are none
   */
  private FieldConstraints constraints(String json) {
    if (json == null || json.isBlank()) {
      return FieldConstraints.NONE;
    }
    JsonNode node = mapper.readTree(json);
    return new FieldConstraints(
        text(node, "pattern"),
        decimal(node, "min"),
        decimal(node, "max"),
        integer(node, "minLength"),
        integer(node, "maxLength"),
        text(node, "unit"),
        referenceKind(text(node, "referenceKind")));
  }

  /**
   * The {@code visibility} column as a rule.
   *
   * @param json the column value, possibly {@code null}
   * @return the rule, or {@code null} when the field is always shown
   */
  private VisibilityRule visibility(String json) {
    if (json == null || json.isBlank()) {
      return null;
    }
    JsonNode node = mapper.readTree(json);
    String field = text(node, "field");
    String operator = text(node, "operator");
    if (field == null || operator == null) {
      return null;
    }
    JsonNode value = node.get("value");
    return new VisibilityRule(
        field,
        VisibilityRule.Operator.ofToken(operator),
        value == null || value.isNull() ? null : value.toString());
  }

  /**
   * A reference kind from its stored token.
   *
   * @param token the token, possibly {@code null}
   * @return the kind, or {@code null} when none was declared
   */
  private FieldConstraints.ReferenceKind referenceKind(String token) {
    if (token == null || token.isBlank()) {
      return null;
    }
    return FieldConstraints.ReferenceKind.valueOf(token.trim().toUpperCase(java.util.Locale.ROOT));
  }

  /**
   * A string member of a JSON object.
   *
   * @param node the object
   * @param name the member
   * @return its text, or {@code null} when absent or null
   */
  private String text(JsonNode node, String name) {
    JsonNode member = node.get(name);
    return member == null || member.isNull() ? null : member.asString();
  }

  /**
   * A decimal member of a JSON object.
   *
   * @param node the object
   * @param name the member
   * @return its value, or {@code null} when absent or null
   */
  private BigDecimal decimal(JsonNode node, String name) {
    JsonNode member = node.get(name);
    return member == null || member.isNull() ? null : new BigDecimal(member.asString());
  }

  /**
   * An integer member of a JSON object.
   *
   * @param node the object
   * @param name the member
   * @return its value, or {@code null} when absent or null
   */
  private Integer integer(JsonNode node, String name) {
    JsonNode member = node.get(name);
    return member == null || member.isNull() ? null : member.asInt();
  }

  /**
   * A PostgreSQL array literal of uuids.
   *
   * @param ids the ids
   * @return {@code {a,b,c}}, which the query casts to {@code uuid[]}
   */
  private String arrayLiteral(Collection<UUID> ids) {
    return ids.stream()
        .filter(java.util.Objects::nonNull)
        .map(UUID::toString)
        .distinct()
        .collect(Collectors.joining(",", "{", "}"));
  }
}
