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
  public UUID categoryOfVersion(UUID categoryVersionId) {
    return jdbc
        .sql(
            """
            select v.location_category_id
            from catalog.location_category_version v
            where v.tenant_id = ?
              and v.id = ?
            """)
        .params(TenantContext.require(), categoryVersionId)
        .query(UUID.class)
        .optional()
        .orElseThrow(
            () ->
                new de.greluc.homeinv.platform.NotFoundException(
                    "location category version", categoryVersionId));
  }

  @Override
  @Transactional(readOnly = true)
  public boolean permitsChildCategory(UUID parentCategoryId, UUID childCategoryId) {
    UUID tenantId = TenantContext.require();
    // Two questions in one statement, and the order matters: a category with no
    // rule at all takes everything (REQ-CORE-047's "optional"), so the absence of
    // rows is a yes rather than a no. Asked the other way round, adding the
    // feature would have closed every tree that had not been configured yet.
    Boolean permitted =
        jdbc.sql(
                """
                select not exists (
                         select 1 from catalog.location_category_child
                         where tenant_id = ? and parent_category_id = ?)
                    or exists (
                         select 1 from catalog.location_category_child
                         where tenant_id = ? and parent_category_id = ?
                           and child_category_id = ?)
                """)
            .params(
                tenantId, parentCategoryId, tenantId, parentCategoryId, childCategoryId)
            .query(Boolean.class)
            .single();
    return Boolean.TRUE.equals(permitted);
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
                   searchable, sortable, facetable, sensitive, expiry, deprecated_at
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
  public List<UUID> itemTypeVersionsByKeys(Collection<String> keys) {
    if (keys == null || keys.isEmpty()) {
      return List.of();
    }
    UUID tenantId = TenantContext.require();
    // Every version of every named type. `published_at` is deliberately not in
    // the predicate: an item is written against the version that was current
    // when it was written, so restricting to the newest one would hide most of
    // a type's items rather than narrow to them.
    return jdbc
        .sql(
            """
            select v.id
            from catalog.item_type_version v
            join catalog.item_type t
              on t.tenant_id = v.tenant_id and t.id = v.item_type_id
            where v.tenant_id = ? and t.key = any(?)
            """)
        .params(tenantId, keys.toArray(String[]::new))
        .query((rs, rowNum) -> rs.getObject("id", UUID.class))
        .list();
  }

  @Override
  @Transactional(readOnly = true)
  public Map<UUID, TypeRegistry.TypeIdentity> typesOfVersions(Collection<UUID> versionIds) {
    if (versionIds == null || versionIds.isEmpty()) {
      return Map.of();
    }
    UUID tenantId = TenantContext.require();
    Map<UUID, TypeRegistry.TypeIdentity> identities = new LinkedHashMap<>();
    jdbc.sql(
            """
            select v.id as version_id, t.key as key, p.key as parent_key
            from catalog.item_type_version v
            join catalog.item_type t
              on t.tenant_id = v.tenant_id and t.id = v.item_type_id
            left join catalog.item_type p
              on p.tenant_id = t.tenant_id and p.id = t.parent_id
            where v.tenant_id = ? and v.id = any(?)
            """)
        .params(tenantId, versionIds.toArray(UUID[]::new))
        .query(
            (rs, rowNum) ->
                Map.entry(
                    rs.getObject("version_id", UUID.class),
                    new TypeRegistry.TypeIdentity(
                        rs.getString("key"), rs.getString("parent_key"))))
        .list()
        .forEach(entry -> identities.put(entry.getKey(), entry.getValue()));
    return identities;
  }

  @Override
  @Transactional(readOnly = true)
  public List<TypeRegistry.ExpiryField> expiryFields() {
    UUID tenantId = TenantContext.require();
    // The same shape `queryableFields` has: grouped by key across every PUBLISHED
    // version, because the overview is a question about the tenant rather than
    // about one type, and two types both calling their date `expiresOn` is one
    // key here. A draft's fields are not included -- nothing is indexed under an
    // unpublished version, so there would be no values to find.
    //
    // A deprecated field keeps its values (REQ-CORE-026) and stops being offered,
    // so it stops being collected too: an overview that kept listing a date
    // nobody can set any more is a list of things nobody can act on.
    return jdbc
        .sql(
            """
            select f.key as key, min(f.labels::text) as labels
            from catalog.field_definition f
            join catalog.item_type_version v
              on v.tenant_id = f.tenant_id and v.id = f.item_type_version_id
            where f.tenant_id = ?
              and v.published_at is not null
              and f.deprecated_at is null
              and f.expiry
            group by f.key
            order by f.key
            """)
        .param(tenantId)
        .query(
            (rs, rowNum) ->
                new TypeRegistry.ExpiryField(rs.getString("key"), strings(rs.getString("labels"))))
        .list();
  }

  @Override
  @Transactional(readOnly = true)
  public List<TypeRegistry.QueryableField> queryableFields() {
    UUID tenantId = TenantContext.require();
    // Grouped by key across every PUBLISHED version, because a query spans types.
    // `count(distinct data_type) = 1` is the rule that keeps a key out when two
    // types disagree about what it holds: `item_attr_index` keeps one column per
    // storage class, so such a key lives in two at once and no single predicate
    // over it means anything. Rare, and left out rather than guessed at.
    //
    // A draft's fields are not queryable: nothing references an unpublished
    // version, so nothing is indexed under it either.
    return jdbc
        .sql(
            """
            select f.key                        as key,
                   min(f.data_type)             as data_type,
                   bool_or(f.searchable)        as filterable,
                   bool_or(f.sortable)          as sortable,
                   bool_or(f.facetable)         as facetable
            from catalog.field_definition f
            join catalog.item_type_version v
              on v.tenant_id = f.tenant_id and v.id = f.item_type_version_id
            where f.tenant_id = ?
              and v.published_at is not null
              and f.deprecated_at is null
              and f.sensitive = false
            group by f.key
            having count(distinct f.data_type) = 1
               and (bool_or(f.searchable) or bool_or(f.sortable) or bool_or(f.facetable))
            order by f.key
            """)
        .param(tenantId)
        .query(
            (rs, rowNum) ->
                new TypeRegistry.QueryableField(
                    rs.getString("key"),
                    FieldDataType.ofToken(rs.getString("data_type")),
                    rs.getBoolean("filterable"),
                    rs.getBoolean("sortable"),
                    rs.getBoolean("facetable")))
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
        rs.getTimestamp("deprecated_at") != null,
        rs.getBoolean("expiry"));
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
