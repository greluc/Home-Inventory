/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.catalog.infrastructure;

import de.greluc.homeinv.catalog.api.AttributeUsage;
import de.greluc.homeinv.catalog.api.FieldAdded;
import de.greluc.homeinv.catalog.api.FieldDefinitionView;
import de.greluc.homeinv.catalog.api.FieldDeprecated;
import de.greluc.homeinv.catalog.api.FieldSearchabilityChanged;
import de.greluc.homeinv.catalog.api.TypeAdministration;
import de.greluc.homeinv.catalog.api.TypeCreated;
import de.greluc.homeinv.catalog.api.TypeKind;
import de.greluc.homeinv.catalog.api.TypeRegistry;
import de.greluc.homeinv.catalog.api.TypeVersionPublished;
import de.greluc.homeinv.catalog.application.JsonSchemaGenerator;
import de.greluc.homeinv.catalog.domain.FieldTightening;
import de.greluc.homeinv.platform.CursorCodec;
import de.greluc.homeinv.platform.TenantContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * The type editor of REQ-CORE-020: types, categories, versions, fields and value lists.
 *
 * <p>An infrastructure adapter rather than a service over a repository, for the reason
 * {@link CatalogProvisioningAdapter} gives: these tables are edited by exactly one caller, the
 * statements are flat, and an aggregate per row would be structure written for nobody. The one rule
 * that is not a statement — whether an inheriting field tightens what it overrides — lives in
 * {@link FieldTightening}, which has no framework and no database and can be read on its own.
 *
 * <p>Every write is one transaction and publishes its event inside it. A consumer therefore never
 * sees a type that is not there, and the audit entry ADR-0020 asks for is written against the same
 * commit as the change it records.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TypeAdministrationAdapter implements TypeAdministration {

  private final JdbcClient jdbc;
  private final ObjectMapper mapper;
  private final TypeRegistry registry;
  private final JsonSchemaGenerator schemas;
  private final ApplicationEventPublisher events;

  /**
   * Every block that stores attributes, so a removal can count and strip what it would destroy.
   *
   * <p>A list rather than two named ports: {@code inventory} and {@code locations} both hold values,
   * a third block holding them would have to be remembered here otherwise, and Spring already knows
   * which beans implement the interface.
   */
  private final List<AttributeUsage> usages;

  private final CursorCodec cursors;

  /** The cap REQ-NFR-010 puts on every page of every collection. */
  private static final int MAX_PAGE = 200;

  /** What each listing's cursor is bound to, so one cannot resume another. */
  private static final String ITEM_TYPE_CURSOR = "catalog-item-types";

  private static final String CATEGORY_CURSOR = "catalog-location-categories";
  private static final String VALUE_LIST_CURSOR = "catalog-value-lists";

  /**
   * Where the last row read sat, for the cursor of the next page.
   *
   * <p>Written by the row mappers below rather than read back off the view, because the views
   * deliberately carry no timestamps: a creation time is not something a client of the type editor
   * needs, and adding one to every record so this class could read it back would be the tail wagging
   * the dog.
   */
  private CursorCodec.Position position;

  // -------------------------------------------------------------------------
  // Item types
  // -------------------------------------------------------------------------

  @Override
  @Transactional
  public ItemTypeView createItemType(CreateItemTypeCommand command, UUID actor) {
    UUID tenantId = TenantContext.require();
    requireKey(command.key());
    if (exists(ITEM_TYPE_KEY_TAKEN, command.key())) {
      throw new TypeKeyTakenException("item type", command.key());
    }
    if (command.parentId() != null) {
      // Named rather than assumed: a parent from another tenant is invisible here,
      // and a parent that does not exist would surface as a foreign key violation
      // three statements later.
      loadItemType(command.parentId())
          .orElseThrow(
              () ->
                  new TypeRegistry.UnknownTypeException(
                      "Parent type " + command.parentId() + " does not exist for this tenant."));
    }

    UUID typeId = UUID.randomUUID();
    jdbc.sql(
            """
            insert into catalog.item_type
                (id, tenant_id, key, parent_id, kind, icon, builtin, created_by, updated_by)
            values (?, ?, ?, ?, ?, ?, false, ?, ?)
            """)
        .params(
            typeId,
            tenantId,
            command.key(),
            command.parentId(),
            command.kind().name(),
            command.icon(),
            actor,
            actor)
        .update();

    UUID versionId = UUID.randomUUID();
    jdbc.sql(
            """
            insert into catalog.item_type_version
                (id, tenant_id, item_type_id, version_number, json_schema, created_by)
            values (?, ?, ?, 1, ?::jsonb, ?)
            """)
        .params(versionId, tenantId, typeId, emptySchema(versionId), actor)
        .update();

    events.publishEvent(new TypeCreated(tenantId, typeId, command.key(), false));
    log.debug("Item type {} created in tenant {}", command.key(), tenantId);
    return loadItemType(typeId).orElseThrow();
  }

  @Override
  @Transactional(readOnly = true)
  public ItemTypePage itemTypes(String cursor, int limit) {
    int size = Math.clamp(limit, 1, MAX_PAGE);
    List<ItemTypeView> rows =
        page(
            ITEM_TYPE_SELECT + ITEM_TYPE_PAGE,
            ITEM_TYPE_SELECT + ITEM_TYPE_PAGE_AFTER,
            cursor,
            size,
            ITEM_TYPE_CURSOR,
            (rs, rowNum) -> itemTypeOf(rs));
    return new ItemTypePage(rows, nextCursor(rows.size(), size, ITEM_TYPE_CURSOR, lastPosition()));
  }

  @Override
  @Transactional
  public ItemTypeView archiveItemType(UUID typeId, UUID actor) {
    ItemTypeView type = loadItemType(typeId).orElseThrow(() -> unknown("item type", typeId));
    if (type.builtin()) {
      throw new IllegalStateException(
          "The built-in type cannot be archived: it is what an item created without a chosen type "
              + "is written against.");
    }
    jdbc.sql(
            """
            update catalog.item_type
            set archived_at = now(), updated_at = now(), updated_by = ?, version = version + 1
            where tenant_id = ? and id = ? and archived_at is null
            """)
        .params(actor, TenantContext.require(), typeId)
        .update();
    return loadItemType(typeId).orElseThrow();
  }

  // -------------------------------------------------------------------------
  // Location categories
  // -------------------------------------------------------------------------

  @Override
  @Transactional
  public CategoryView createCategory(CreateCategoryCommand command, UUID actor) {
    UUID tenantId = TenantContext.require();
    requireKey(command.key());
    if (exists(CATEGORY_KEY_TAKEN, command.key())) {
      throw new TypeKeyTakenException("location category", command.key());
    }

    UUID categoryId = UUID.randomUUID();
    jdbc.sql(
            """
            insert into catalog.location_category
                (id, tenant_id, key, labels, icon, is_mobile, builtin, created_by, updated_by)
            values (?, ?, ?, ?::jsonb, null, ?, false, ?, ?)
            """)
        .params(
            categoryId,
            tenantId,
            command.key(),
            json(command.labels()),
            command.mobile(),
            actor,
            actor)
        .update();

    UUID versionId = UUID.randomUUID();
    jdbc.sql(
            """
            insert into catalog.location_category_version
                (id, tenant_id, location_category_id, version_number, json_schema, created_by)
            values (?, ?, ?, 1, ?::jsonb, ?)
            """)
        .params(versionId, tenantId, categoryId, emptySchema(versionId), actor)
        .update();

    events.publishEvent(new TypeCreated(tenantId, categoryId, command.key(), true));
    return loadCategory(categoryId).orElseThrow();
  }

  @Override
  @Transactional(readOnly = true)
  public CategoryPage categories(String cursor, int limit) {
    int size = Math.clamp(limit, 1, MAX_PAGE);
    List<CategoryView> rows =
        page(
            CATEGORY_SELECT + CATEGORY_PAGE,
            CATEGORY_SELECT + CATEGORY_PAGE_AFTER,
            cursor,
            size,
            CATEGORY_CURSOR,
            (rs, rowNum) -> categoryOf(rs));
    return new CategoryPage(rows, nextCursor(rows.size(), size, CATEGORY_CURSOR, lastPosition()));
  }

  @Override
  @Transactional
  public CategoryView archiveCategory(UUID categoryId, UUID actor) {
    loadCategory(categoryId).orElseThrow(() -> unknown("location category", categoryId));
    jdbc.sql(
            """
            update catalog.location_category
            set archived_at = now(), updated_at = now(), updated_by = ?, version = version + 1
            where tenant_id = ? and id = ? and archived_at is null
            """)
        .params(actor, TenantContext.require(), categoryId)
        .update();
    return loadCategory(categoryId).orElseThrow();
  }

  @Override
  @Transactional(readOnly = true)
  public ChildCategoryRuleView childCategories(UUID categoryId) {
    loadCategory(categoryId).orElseThrow(() -> unknown("location category", categoryId));
    return new ChildCategoryRuleView(categoryId, permittedChildren(categoryId));
  }

  @Override
  @Transactional
  public ChildCategoryRuleView setChildCategories(
      UUID categoryId, List<UUID> permitted, UUID actor) {

    UUID tenantId = TenantContext.require();
    loadCategory(categoryId).orElseThrow(() -> unknown("location category", categoryId));

    // Order preserved and duplicates dropped, because the caller sent a set and
    // wrote it as a list: two of the same id is one rule, and refusing the request
    // over it would be pedantry about a request whose meaning is not in doubt.
    List<UUID> wanted = new ArrayList<>(new LinkedHashSet<>(permitted));
    if (wanted.size() > MAX_PAGE) {
      throw new IllegalArgumentException(
          "A category takes at most " + MAX_PAGE + " permitted child categories.");
    }
    for (UUID child : wanted) {
      // Checked one by one rather than left to the foreign key: the constraint
      // would refuse the whole request without saying which id was wrong, and a
      // caller reconciling a rule set needs to know that.
      loadCategory(child).orElseThrow(() -> unknown("location category", child));
    }

    // Replaced whole. Working out which rows to add and which to drop would be the
    // same two statements and a diff that can be wrong; this cannot.
    jdbc.sql(
            """
            delete from catalog.location_category_child
            where tenant_id = ? and parent_category_id = ?
            """)
        .params(tenantId, categoryId)
        .update();
    for (UUID child : wanted) {
      jdbc.sql(
              """
              insert into catalog.location_category_child
                  (tenant_id, parent_category_id, child_category_id, created_by)
              values (?, ?, ?, ?)
              """)
          .params(tenantId, categoryId, child, actor)
          .update();
    }

    log.info(
        "Location category {} now takes {} child categor(ies) in tenant {}.",
        categoryId,
        wanted.size(),
        tenantId);
    return new ChildCategoryRuleView(categoryId, permittedChildren(categoryId));
  }

  /**
   * The ids a category permits underneath it, oldest rule first.
   *
   * @param categoryId the category
   * @return the permitted categories, empty when the category is unrestricted
   */
  private List<UUID> permittedChildren(UUID categoryId) {
    return jdbc
        .sql(
            """
            select child_category_id
            from catalog.location_category_child
            where tenant_id = ? and parent_category_id = ?
            order by created_at, child_category_id
            """)
        .params(TenantContext.require(), categoryId)
        .query(UUID.class)
        .list();
  }

  // -------------------------------------------------------------------------
  // Versions
  // -------------------------------------------------------------------------

  @Override
  @Transactional
  public VersionView draftVersion(UUID ownerId, UUID actor) {
    UUID tenantId = TenantContext.require();
    boolean category = isCategory(ownerId);

    Integer existingDraft =
        jdbc.sql(category ? CATEGORY_DRAFT_NUMBER : ITEM_TYPE_DRAFT_NUMBER)
            .params(tenantId, ownerId)
            .query(Integer.class)
            .optional()
            .orElse(null);
    if (existingDraft != null) {
      throw new IllegalStateException(
          "Version " + existingDraft + " is already a draft. There is one draft at a time, because "
              + "two would be two answers to what is being edited.");
    }

    int next =
        jdbc.sql(category ? CATEGORY_NEXT_NUMBER : ITEM_TYPE_NEXT_NUMBER)
            .params(tenantId, ownerId)
            .query(Integer.class)
            .single();

    UUID versionId = UUID.randomUUID();
    jdbc.sql(category ? INSERT_CATEGORY_VERSION : INSERT_ITEM_TYPE_VERSION)
        .params(versionId, tenantId, ownerId, next, emptySchema(versionId), actor)
        .update();

    // The draft starts as a copy of what is published, minus the fields that were
    // materialised from a parent: those are re-derived at the next publish, from
    // whatever the parent says then. Copying them too would freeze an ancestor's
    // old shape into a version that has not been published yet.
    publishedVersion(ownerId, category)
        .ifPresent(
            previous ->
                jdbc.sql(
                        """
                        insert into catalog.field_definition
                            (id, tenant_id, item_type_version_id, location_category_version_id,
                             key, data_type, labels, help_texts, required, default_value,
                             constraints, value_list_id, visibility, field_group, display_order,
                             searchable, sortable, facetable, sensitive, deprecated_at,
                             created_by, updated_by)
                        select uuidv7(), tenant_id,
                               case when ? then null else ? end,
                               case when ? then ? else null end,
                               key, data_type, labels, help_texts, required, default_value,
                               constraints, value_list_id, visibility, field_group, display_order,
                               searchable, sortable, facetable, sensitive, deprecated_at, ?, ?
                        from catalog.field_definition
                        where tenant_id = ?
                          and (item_type_version_id = ? or location_category_version_id = ?)
                          and inherited_from is null
                        """)
                    .params(
                        category, versionId, category, versionId, actor, actor,
                        tenantId, previous, previous)
                    .update());

    return version(versionId);
  }

  @Override
  @Transactional(readOnly = true)
  public VersionView version(UUID versionId) {
    Version meta = loadVersion(versionId);
    return new VersionView(
        versionId, meta.ownerId(), meta.number(), meta.published(), registry.fields(versionId));
  }

  @Override
  @Transactional
  public VersionView publish(UUID versionId, UUID actor) {
    UUID tenantId = TenantContext.require();
    Version meta = loadVersion(versionId);
    if (meta.published()) {
      throw new VersionFrozenException(versionId);
    }

    if (!meta.category()) {
      materialiseInheritance(versionId, meta.ownerId(), actor);
    }

    List<FieldDefinitionView> fields = registry.fields(versionId);
    String schema = schemas.generate(versionId, fields, registry::valueListEntries);

    jdbc.sql(meta.category() ? PUBLISH_CATEGORY_VERSION : PUBLISH_ITEM_TYPE_VERSION)
        .params(schema, tenantId, versionId)
        .update();

    events.publishEvent(
        new TypeVersionPublished(tenantId, meta.ownerId(), versionId, meta.number()));
    log.info(
        "Version {} of {} published in tenant {} with {} field(s)",
        meta.number(),
        meta.ownerId(),
        tenantId,
        fields.size());
    return version(versionId);
  }

  /**
   * Copies the parent's fields in, and refuses an override that widens one (REQ-CORE-024).
   *
   * <p>Only the immediate parent's published version is read, and that is enough: it was itself
   * materialised when it was published, so it already carries everything it inherited. The chain is
   * therefore one step deep however long it is.
   *
   * @param versionId the draft being published
   * @param typeId the type it belongs to
   * @param actor the authenticated user
   * @throws ConstraintLoosenedException when an override is looser than what it overrides
   */
  private void materialiseInheritance(UUID versionId, UUID typeId, UUID actor) {
    UUID tenantId = TenantContext.require();
    UUID parentId =
        jdbc.sql("select parent_id from catalog.item_type where tenant_id = ? and id = ?")
            .params(tenantId, typeId)
            .query(UUID.class)
            .optional()
            .orElse(null);
    if (parentId == null) {
      return;
    }
    Optional<UUID> parentVersion = publishedVersion(parentId, false);
    if (parentVersion.isEmpty()) {
      throw new TypeRegistry.UnknownTypeException(
          "The parent type has no published version, so there is nothing to inherit. Publish it "
              + "first.");
    }

    Map<String, FieldDefinitionView> own = byKey(registry.fields(versionId));
    for (FieldDefinitionView inherited : registry.fields(parentVersion.get())) {
      FieldDefinitionView override = own.get(inherited.key());
      if (override == null) {
        copyField(inherited, versionId, actor);
        continue;
      }
      FieldTightening.widens(override, inherited)
          .ifPresent(
              widened -> {
                throw new ConstraintLoosenedException(inherited.key(), widened);
              });
    }
  }

  /**
   * Writes one inherited field into the version being published.
   *
   * @param source the parent's definition
   * @param versionId the version receiving the copy
   * @param actor the authenticated user
   */
  private void copyField(FieldDefinitionView source, UUID versionId, UUID actor) {
    jdbc.sql(
            """
            insert into catalog.field_definition
                (id, tenant_id, item_type_version_id, key, data_type, labels, help_texts,
                 required, default_value, constraints, value_list_id, visibility, field_group,
                 display_order, searchable, sortable, facetable, sensitive, deprecated_at,
                 inherited_from, created_by, updated_by)
            select uuidv7(), tenant_id, ?, key, data_type, labels, help_texts,
                   required, default_value, constraints, value_list_id, visibility, field_group,
                   display_order, searchable, sortable, facetable, sensitive, deprecated_at,
                   id, ?, ?
            from catalog.field_definition
            where tenant_id = ? and id = ?
            """)
        .params(versionId, actor, actor, TenantContext.require(), source.id())
        .update();
  }

  // -------------------------------------------------------------------------
  // Fields
  // -------------------------------------------------------------------------

  @Override
  @Transactional
  public FieldDefinitionView addField(UUID versionId, FieldCommand command, UUID actor) {
    UUID tenantId = TenantContext.require();
    Version meta = requireDraft(versionId);
    requireKey(command.key());
    requireFieldShape(command);
    if (registry.fields(versionId).stream().anyMatch(field -> field.key().equals(command.key()))) {
      throw new TypeKeyTakenException("field", command.key());
    }

    UUID fieldId = UUID.randomUUID();
    jdbc.sql(
            """
            insert into catalog.field_definition
                (id, tenant_id, item_type_version_id, location_category_version_id, key, data_type,
                 labels, help_texts, required, default_value, constraints, value_list_id,
                 visibility, field_group, display_order, searchable, sortable, facetable,
                 sensitive, created_by, updated_by)
            values (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?::jsonb, ?::jsonb, ?, ?::jsonb, ?, ?,
                    ?, ?, ?, ?, ?, ?)
            """)
        .params(
            fieldId,
            tenantId,
            meta.category() ? null : versionId,
            meta.category() ? versionId : null,
            command.key(),
            command.dataType().token(),
            json(command.labels()),
            json(command.helpTexts()),
            command.required(),
            command.defaultValue(),
            constraintsJson(command),
            command.valueListId(),
            visibilityJson(command),
            command.group(),
            command.displayOrder(),
            command.searchable(),
            command.sortable(),
            command.facetable(),
            command.sensitive(),
            actor,
            actor)
        .update();

    events.publishEvent(new FieldAdded(tenantId, versionId, fieldId, command.key()));
    FieldDefinitionView added = field(fieldId);
    if (added.projected()) {
      events.publishEvent(
          new FieldSearchabilityChanged(tenantId, versionId, fieldId, command.key(), true));
    }
    return added;
  }

  @Override
  @Transactional
  public FieldDefinitionView updateField(UUID fieldId, FieldCommand command, UUID actor) {
    UUID tenantId = TenantContext.require();
    FieldDefinitionView before = field(fieldId);
    Version meta = requireDraft(versionOf(fieldId));
    requireFieldShape(command);

    jdbc.sql(
            """
            update catalog.field_definition
            set data_type = ?, labels = ?::jsonb, help_texts = ?::jsonb, required = ?,
                default_value = ?::jsonb, constraints = ?::jsonb, value_list_id = ?,
                visibility = ?::jsonb, field_group = ?, display_order = ?, searchable = ?,
                sortable = ?, facetable = ?, sensitive = ?, updated_at = now(), updated_by = ?,
                version = version + 1
            where tenant_id = ? and id = ?
            """)
        .params(
            command.dataType().token(),
            json(command.labels()),
            json(command.helpTexts()),
            command.required(),
            command.defaultValue(),
            constraintsJson(command),
            command.valueListId(),
            visibilityJson(command),
            command.group(),
            command.displayOrder(),
            command.searchable(),
            command.sortable(),
            command.facetable(),
            command.sensitive(),
            actor,
            tenantId,
            fieldId)
        .update();

    FieldDefinitionView after = field(fieldId);
    if (before.projected() != after.projected()) {
      events.publishEvent(
          new FieldSearchabilityChanged(
              tenantId, meta.id(), fieldId, after.key(), after.projected()));
    }
    return after;
  }

  @Override
  @Transactional
  public void deprecateField(UUID fieldId, UUID actor) {
    UUID tenantId = TenantContext.require();
    FieldDefinitionView field = field(fieldId);
    Version meta = requireDraft(versionOf(fieldId));
    jdbc.sql(
            """
            update catalog.field_definition
            set deprecated_at = now(), updated_at = now(), updated_by = ?, version = version + 1
            where tenant_id = ? and id = ? and deprecated_at is null
            """)
        .params(actor, tenantId, fieldId)
        .update();
    events.publishEvent(new FieldDeprecated(tenantId, meta.id(), fieldId, field.key()));
  }

  @Override
  @Transactional(readOnly = true)
  public FieldRemovalPreview previewFieldRemoval(UUID fieldId) {
    FieldDefinitionView field = field(fieldId);
    List<UUID> versions = versionsCarrying(fieldId);
    long affected =
        usages.stream().mapToLong(usage -> usage.countCarrying(field.key(), versions)).sum();
    return new FieldRemovalPreview(fieldId, field.key(), affected);
  }

  @Override
  @Transactional
  public void removeField(UUID fieldId, UUID actor) {
    UUID tenantId = TenantContext.require();
    FieldDefinitionView field = field(fieldId);
    if (!field.deprecated()) {
      throw new IllegalStateException(
          "Field '" + field.key() + "' is not deprecated. REQ-CORE-026 hides a field before "
              + "anything destroys its values, so that a change of mind costs nothing.");
    }

    List<UUID> versions = versionsCarrying(fieldId);
    long stripped = usages.stream().mapToLong(usage -> usage.strip(field.key(), versions)).sum();

    jdbc.sql(
            """
            delete from catalog.field_definition
            where tenant_id = ? and key = ?
              and (item_type_version_id = any (?::uuid[]) or location_category_version_id = any (?::uuid[]))
            """)
        .params(tenantId, field.key(), array(versions), array(versions))
        .update();

    // Every published version of this owner declared the field, so every one of
    // their schemas mentions it. They are frozen against edits and not against
    // this: the tenant asked for the values to be destroyed and was shown how
    // many there were, and a schema still demanding a key nothing may carry
    // would refuse every subsequent write.
    for (UUID versionId : versions) {
      Version meta = loadVersion(versionId);
      if (!meta.published()) {
        continue;
      }
      String schema =
          schemas.generate(versionId, registry.fields(versionId), registry::valueListEntries);
      jdbc.sql(meta.category() ? RESCHEMA_CATEGORY_VERSION : RESCHEMA_ITEM_TYPE_VERSION)
          .params(schema, tenantId, versionId)
          .update();
    }
    log.info(
        "Field '{}' removed in tenant {} by {}; {} row(s) lost a value",
        field.key(),
        tenantId,
        actor,
        stripped);
  }

  // -------------------------------------------------------------------------
  // Value lists
  // -------------------------------------------------------------------------

  @Override
  @Transactional
  public ValueListView createValueList(CreateValueListCommand command, UUID actor) {
    UUID tenantId = TenantContext.require();
    requireKey(command.key());
    if (exists(VALUE_LIST_KEY_TAKEN, command.key())) {
      throw new TypeKeyTakenException("value list", command.key());
    }
    UUID listId = UUID.randomUUID();
    jdbc.sql(
            """
            insert into catalog.value_list (id, tenant_id, key, labels, created_by, updated_by)
            values (?, ?, ?, ?::jsonb, ?, ?)
            """)
        .params(listId, tenantId, command.key(), json(command.labels()), actor, actor)
        .update();
    return valueList(listId);
  }

  @Override
  @Transactional(readOnly = true)
  public ValueListPage valueLists(String cursor, int limit) {
    int size = Math.clamp(limit, 1, MAX_PAGE);
    List<UUID> ids =
        page(
            VALUE_LIST_PAGE,
            VALUE_LIST_PAGE_AFTER,
            cursor,
            size,
            VALUE_LIST_CURSOR,
            (rs, rowNum) -> {
              position = new CursorCodec.Position(
                  rs.getTimestamp("created_at").toInstant(), rs.getObject("id", UUID.class));
              return rs.getObject("id", UUID.class);
            });
    List<ValueListView> rows = ids.stream().map(this::valueList).toList();
    return new ValueListPage(rows, nextCursor(rows.size(), size, VALUE_LIST_CURSOR, position));
  }

  @Override
  @Transactional
  public ValueListEntryView addValueListEntry(
      UUID valueListId, AddEntryCommand command, UUID actor) {
    UUID tenantId = TenantContext.require();
    boolean known =
        jdbc.sql("select count(*) from catalog.value_list where tenant_id = ? and id = ?")
                .params(tenantId, valueListId)
                .query(Long.class)
                .single()
            > 0;
    if (!known) {
      throw unknown("value list", valueListId);
    }
    if (command.value() == null || command.value().isBlank()) {
      throw new IllegalArgumentException("A value list entry needs a value.");
    }
    boolean taken =
        jdbc.sql(
                    """
                    select count(*) from catalog.value_list_entry
                    where tenant_id = ? and value_list_id = ? and value = ?
                    """)
                .params(tenantId, valueListId, command.value())
                .query(Long.class)
                .single()
            > 0;
    if (taken) {
      throw new TypeKeyTakenException("value", command.value());
    }

    UUID entryId = UUID.randomUUID();
    jdbc.sql(
            """
            insert into catalog.value_list_entry
                (id, tenant_id, value_list_id, value, labels, display_order, created_by, updated_by)
            values (?, ?, ?, ?, ?::jsonb, ?, ?, ?)
            """)
        .params(
            entryId,
            tenantId,
            valueListId,
            command.value(),
            json(command.labels()),
            command.displayOrder(),
            actor,
            actor)
        .update();
    return entry(entryId);
  }

  @Override
  @Transactional
  public void archiveValueListEntry(UUID entryId, UUID actor) {
    int changed =
        jdbc.sql(
                """
                update catalog.value_list_entry
                set archived_at = now(), updated_at = now(), updated_by = ?, version = version + 1
                where tenant_id = ? and id = ? and archived_at is null
                """)
            .params(actor, TenantContext.require(), entryId)
            .update();
    if (changed == 0) {
      throw unknown("value list entry", entryId);
    }
  }

  // -------------------------------------------------------------------------
  // Reading
  // -------------------------------------------------------------------------

  // ---------------------------------------------------------------------------
  // Statements
  // ---------------------------------------------------------------------------
  //
  // Every one of these is a complete literal, and the pairs that differ only by a
  // table name are written out twice on purpose. A statement assembled from a
  // variable is a statement a reviewer has to reconstruct before they can read
  // it, and `ArchitectureRulesTest` refuses the shape outright rather than trying
  // to tell a safe interpolation from an unsafe one — which is the right rule,
  // because the difference is one refactor away from disappearing (REQ-SEC-031).

  private static final String ITEM_TYPE_PAGE =
      " where t.tenant_id = ? order by t.created_at, t.id limit ?";
  private static final String ITEM_TYPE_PAGE_AFTER =
      " where t.tenant_id = ? and (t.created_at > ? or (t.created_at = ? and t.id > ?))"
          + " order by t.created_at, t.id limit ?";
  private static final String ITEM_TYPE_BY_ID = " where t.tenant_id = ? and t.id = ?";
  private static final String CATEGORY_PAGE =
      " where c.tenant_id = ? order by c.created_at, c.id limit ?";
  private static final String CATEGORY_PAGE_AFTER =
      " where c.tenant_id = ? and (c.created_at > ? or (c.created_at = ? and c.id > ?))"
          + " order by c.created_at, c.id limit ?";

  private static final String VALUE_LIST_PAGE =
      "select id, created_at from catalog.value_list where tenant_id = ?"
          + " order by created_at, id limit ?";
  private static final String VALUE_LIST_PAGE_AFTER =
      "select id, created_at from catalog.value_list where tenant_id = ?"
          + " and (created_at > ? or (created_at = ? and id > ?)) order by created_at, id limit ?";
  private static final String CATEGORY_BY_ID = " where c.tenant_id = ? and c.id = ?";

  private static final String ITEM_TYPE_KEY_TAKEN =
      "select count(*) from catalog.item_type where tenant_id = ? and key = ?";
  private static final String CATEGORY_KEY_TAKEN =
      "select count(*) from catalog.location_category where tenant_id = ? and key = ?";
  private static final String VALUE_LIST_KEY_TAKEN =
      "select count(*) from catalog.value_list where tenant_id = ? and key = ?";

  private static final String ITEM_TYPE_DRAFT_NUMBER =
      "select version_number from catalog.item_type_version"
          + " where tenant_id = ? and item_type_id = ? and published_at is null";
  private static final String CATEGORY_DRAFT_NUMBER =
      "select version_number from catalog.location_category_version"
          + " where tenant_id = ? and location_category_id = ? and published_at is null";

  private static final String ITEM_TYPE_NEXT_NUMBER =
      "select coalesce(max(version_number), 0) + 1 from catalog.item_type_version"
          + " where tenant_id = ? and item_type_id = ?";
  private static final String CATEGORY_NEXT_NUMBER =
      "select coalesce(max(version_number), 0) + 1 from catalog.location_category_version"
          + " where tenant_id = ? and location_category_id = ?";

  private static final String INSERT_ITEM_TYPE_VERSION =
      "insert into catalog.item_type_version"
          + " (id, tenant_id, item_type_id, version_number, json_schema, created_by)"
          + " values (?, ?, ?, ?, ?::jsonb, ?)";
  private static final String INSERT_CATEGORY_VERSION =
      "insert into catalog.location_category_version"
          + " (id, tenant_id, location_category_id, version_number, json_schema, created_by)"
          + " values (?, ?, ?, ?, ?::jsonb, ?)";

  private static final String PUBLISH_ITEM_TYPE_VERSION =
      "update catalog.item_type_version set json_schema = ?::jsonb, published_at = now(),"
          + " version = version + 1 where tenant_id = ? and id = ?";
  private static final String PUBLISH_CATEGORY_VERSION =
      "update catalog.location_category_version set json_schema = ?::jsonb, published_at = now(),"
          + " version = version + 1 where tenant_id = ? and id = ?";

  private static final String RESCHEMA_ITEM_TYPE_VERSION =
      "update catalog.item_type_version set json_schema = ?::jsonb, version = version + 1"
          + " where tenant_id = ? and id = ?";
  private static final String RESCHEMA_CATEGORY_VERSION =
      "update catalog.location_category_version set json_schema = ?::jsonb, version = version + 1"
          + " where tenant_id = ? and id = ?";

  private static final String ITEM_TYPE_VERSIONS =
      "select id from catalog.item_type_version where tenant_id = ? and item_type_id = ?"
          + " order by version_number";
  private static final String CATEGORY_VERSIONS =
      "select id from catalog.location_category_version where tenant_id = ?"
          + " and location_category_id = ? order by version_number";

  private static final String ITEM_TYPE_PUBLISHED =
      "select id from catalog.item_type_version where tenant_id = ? and item_type_id = ?"
          + " and published_at is not null order by version_number desc limit 1";
  private static final String CATEGORY_PUBLISHED =
      "select id from catalog.location_category_version where tenant_id = ?"
          + " and location_category_id = ? and published_at is not null"
          + " order by version_number desc limit 1";

  private static final String ITEM_TYPE_SELECT =
      """
      select t.id, t.key, t.kind, t.parent_id, t.icon, t.builtin, t.archived_at, t.created_at,
             (select v.id from catalog.item_type_version v
               where v.tenant_id = t.tenant_id and v.item_type_id = t.id
                 and v.published_at is not null
               order by v.version_number desc limit 1) as published_id,
             (select v.id from catalog.item_type_version v
               where v.tenant_id = t.tenant_id and v.item_type_id = t.id
                 and v.published_at is null
               order by v.version_number desc limit 1) as draft_id
      from catalog.item_type t
      """;

  private static final String CATEGORY_SELECT =
      """
      select c.id, c.key, c.labels, c.is_mobile, c.builtin, c.archived_at, c.created_at,
             (select v.id from catalog.location_category_version v
               where v.tenant_id = c.tenant_id and v.location_category_id = c.id
                 and v.published_at is not null
               order by v.version_number desc limit 1) as published_id,
             (select v.id from catalog.location_category_version v
               where v.tenant_id = c.tenant_id and v.location_category_id = c.id
                 and v.published_at is null
               order by v.version_number desc limit 1) as draft_id
      from catalog.location_category c
      """;

  /**
   * Reads one page, from the start or from where a cursor left off.
   *
   * @param first the statement for the first page
   * @param after the statement for a later page, taking the cursor's position
   * @param cursor the cursor, or {@code null}
   * @param size how many rows at most
   * @param fingerprint what the cursor must be bound to
   * @param mapper how to read a row
   * @param <T> what a row becomes
   * @return the rows
   */
  private <T> List<T> page(
      String first,
      String after,
      String cursor,
      int size,
      String fingerprint,
      org.springframework.jdbc.core.RowMapper<T> mapper) {
    UUID tenantId = TenantContext.require();
    position = null;
    if (cursor == null || cursor.isBlank()) {
      return jdbc.sql(first).params(tenantId, size).query(mapper).list();
    }
    // Throws when the cursor was tampered with or belongs to another listing.
    CursorCodec.Position from = cursors.decode(cursor, fingerprint);
    return jdbc.sql(after)
        .params(tenantId, from.createdAt(), from.createdAt(), from.id(), size)
        .query(mapper)
        .list();
  }

  /**
   * The cursor for the next page, or none when this page was the last.
   *
   * @param read how many rows came back
   * @param size how many were asked for
   * @param fingerprint what the cursor is bound to
   * @param last where the last row sat
   * @return the cursor, or {@code null}
   */
  private String nextCursor(int read, int size, String fingerprint, CursorCodec.Position last) {
    // A cursor only when the page was full: a short page is the last one, and a
    // cursor for it would cost a client a request to discover that.
    return read == size && last != null ? cursors.encode(last, fingerprint) : null;
  }

  /**
   * Where the last row of the page just read sat.
   *
   * @return the position, or {@code null} when the page was empty
   */
  private CursorCodec.Position lastPosition() {
    return position;
  }

  /**
   * One item type, or nothing when this tenant has no such type.
   *
   * @param typeId the type
   * @return the view
   */
  private Optional<ItemTypeView> loadItemType(UUID typeId) {
    return jdbc.sql(ITEM_TYPE_SELECT + ITEM_TYPE_BY_ID)
        .params(TenantContext.require(), typeId)
        .query((rs, rowNum) -> itemTypeOf(rs))
        .optional();
  }

  /**
   * One location category, or nothing when this tenant has no such category.
   *
   * @param categoryId the category
   * @return the view
   */
  private Optional<CategoryView> loadCategory(UUID categoryId) {
    return jdbc.sql(CATEGORY_SELECT + CATEGORY_BY_ID)
        .params(TenantContext.require(), categoryId)
        .query((rs, rowNum) -> categoryOf(rs))
        .optional();
  }

  /**
   * Maps an item type row.
   *
   * @param rs the row
   * @return the view
   * @throws java.sql.SQLException when the row cannot be read
   */
  private ItemTypeView itemTypeOf(java.sql.ResultSet rs) throws java.sql.SQLException {
    position =
        new CursorCodec.Position(
            rs.getTimestamp("created_at").toInstant(), rs.getObject("id", UUID.class));
    return new ItemTypeView(
        rs.getObject("id", UUID.class),
        rs.getString("key"),
        TypeKind.valueOf(rs.getString("kind")),
        rs.getObject("parent_id", UUID.class),
        rs.getString("icon"),
        rs.getBoolean("builtin"),
        rs.getTimestamp("archived_at") != null,
        rs.getObject("published_id", UUID.class),
        rs.getObject("draft_id", UUID.class));
  }

  /**
   * Maps a location category row.
   *
   * @param rs the row
   * @return the view
   * @throws java.sql.SQLException when the row cannot be read
   */
  private CategoryView categoryOf(java.sql.ResultSet rs) throws java.sql.SQLException {
    position =
        new CursorCodec.Position(
            rs.getTimestamp("created_at").toInstant(), rs.getObject("id", UUID.class));
    return new CategoryView(
        rs.getObject("id", UUID.class),
        rs.getString("key"),
        labels(rs.getString("labels")),
        rs.getBoolean("is_mobile"),
        rs.getBoolean("builtin"),
        rs.getTimestamp("archived_at") != null,
        rs.getObject("published_id", UUID.class),
        rs.getObject("draft_id", UUID.class));
  }

  /**
   * One value list with its entries.
   *
   * @param listId the list
   * @return the view
   */
  private ValueListView valueList(UUID listId) {
    UUID tenantId = TenantContext.require();
    Map<String, String> labels =
        jdbc.sql("select labels::text from catalog.value_list where tenant_id = ? and id = ?")
            .params(tenantId, listId)
            .query(String.class)
            .optional()
            .map(this::labels)
            .orElseThrow(() -> unknown("value list", listId));
    String key =
        jdbc.sql("select key from catalog.value_list where tenant_id = ? and id = ?")
            .params(tenantId, listId)
            .query(String.class)
            .single();
    List<ValueListEntryView> entries =
        jdbc.sql(
                """
                select id, value, labels::text as labels, display_order, archived_at
                from catalog.value_list_entry
                where tenant_id = ? and value_list_id = ?
                order by display_order, value
                """)
            .params(tenantId, listId)
            .query(
                (rs, rowNum) ->
                    new ValueListEntryView(
                        rs.getObject("id", UUID.class),
                        rs.getString("value"),
                        labels(rs.getString("labels")),
                        rs.getInt("display_order"),
                        rs.getTimestamp("archived_at") != null))
            .list();
    return new ValueListView(listId, key, labels, entries);
  }

  /**
   * One value list entry.
   *
   * @param entryId the entry
   * @return the view
   */
  private ValueListEntryView entry(UUID entryId) {
    return jdbc
        .sql(
            """
            select id, value, labels::text as labels, display_order, archived_at
            from catalog.value_list_entry where tenant_id = ? and id = ?
            """)
        .params(TenantContext.require(), entryId)
        .query(
            (rs, rowNum) ->
                new ValueListEntryView(
                    rs.getObject("id", UUID.class),
                    rs.getString("value"),
                    labels(rs.getString("labels")),
                    rs.getInt("display_order"),
                    rs.getTimestamp("archived_at") != null))
        .optional()
        .orElseThrow(() -> unknown("value list entry", entryId));
  }

  /**
   * One field definition, whichever version it belongs to.
   *
   * @param fieldId the definition
   * @return the view
   */
  private FieldDefinitionView field(UUID fieldId) {
    UUID versionId = versionOf(fieldId);
    return registry.fields(versionId).stream()
        .filter(candidate -> candidate.id().equals(fieldId))
        .findFirst()
        .orElseThrow(() -> unknown("field", fieldId));
  }

  /**
   * The version a field belongs to.
   *
   * @param fieldId the definition
   * @return the version id
   */
  private UUID versionOf(UUID fieldId) {
    return jdbc
        .sql(
            """
            select coalesce(item_type_version_id, location_category_version_id)
            from catalog.field_definition where tenant_id = ? and id = ?
            """)
        .params(TenantContext.require(), fieldId)
        .query(UUID.class)
        .optional()
        .orElseThrow(() -> unknown("field", fieldId));
  }

  /**
   * Every version of the owner whose field this is.
   *
   * <p>A removal reaches all of them: the key is what items carry, and leaving it declared in an
   * older version would leave a schema demanding something nothing may hold.
   *
   * @param fieldId the definition
   * @return the version ids, oldest first
   */
  private List<UUID> versionsCarrying(UUID fieldId) {
    UUID tenantId = TenantContext.require();
    UUID versionId = versionOf(fieldId);
    Version meta = loadVersion(versionId);
    return jdbc.sql(meta.category() ? CATEGORY_VERSIONS : ITEM_TYPE_VERSIONS)
        .params(tenantId, meta.ownerId())
        .query(UUID.class)
        .list();
  }

  /**
   * The newest published version of a type or category.
   *
   * @param ownerId the type or category
   * @param category whether it is a category
   * @return the version, or empty when nothing is published yet
   */
  private Optional<UUID> publishedVersion(UUID ownerId, boolean category) {
    return jdbc.sql(category ? CATEGORY_PUBLISHED : ITEM_TYPE_PUBLISHED)
        .params(TenantContext.require(), ownerId)
        .query(UUID.class)
        .optional();
  }

  /**
   * Reads a version's identity and state.
   *
   * @param versionId the version
   * @return what it belongs to, its number and whether it is frozen
   */
  private Version loadVersion(UUID versionId) {
    UUID tenantId = TenantContext.require();
    return jdbc
        .sql(
            """
            select id, item_type_id as owner_id, version_number, published_at, false as category
            from catalog.item_type_version where tenant_id = ? and id = ?
            union all
            select id, location_category_id as owner_id, version_number, published_at, true as category
            from catalog.location_category_version where tenant_id = ? and id = ?
            """)
        .params(tenantId, versionId, tenantId, versionId)
        .query(
            (rs, rowNum) ->
                new Version(
                    rs.getObject("id", UUID.class),
                    rs.getObject("owner_id", UUID.class),
                    rs.getInt("version_number"),
                    rs.getTimestamp("published_at") != null,
                    rs.getBoolean("category")))
        .optional()
        .orElseThrow(() -> unknown("type version", versionId));
  }

  /**
   * Reads a version and refuses a published one.
   *
   * @param versionId the version
   * @return its identity and state
   * @throws VersionFrozenException when it is published
   */
  private Version requireDraft(UUID versionId) {
    Version meta = loadVersion(versionId);
    if (meta.published()) {
      throw new VersionFrozenException(versionId);
    }
    return meta;
  }

  /**
   * Whether an id names a location category rather than an item type.
   *
   * @param ownerId the id
   * @return true for a category
   * @throws TypeRegistry.UnknownTypeException when it is neither
   */
  private boolean isCategory(UUID ownerId) {
    UUID tenantId = TenantContext.require();
    Boolean category =
        jdbc.sql(
                """
                select false from catalog.item_type where tenant_id = ? and id = ?
                union all
                select true from catalog.location_category where tenant_id = ? and id = ?
                """)
            .params(tenantId, ownerId, tenantId, ownerId)
            .query(Boolean.class)
            .optional()
            .orElseThrow(() -> unknown("type or category", ownerId));
    return category;
  }

  // -------------------------------------------------------------------------
  // Small helpers
  // -------------------------------------------------------------------------

  /**
   * Whether a key is already used in a table of this tenant.
   *
   * @param table the qualified table name
   * @param key the key
   * @return true when a row already carries it
   */
  private boolean exists(String statement, String key) {
    return jdbc.sql(statement).params(TenantContext.require(), key).query(Long.class).single() > 0;
  }

  /**
   * Refuses a key the database would refuse, before the insert.
   *
   * @param key the key a tenant chose
   * @throws IllegalArgumentException when it is not a key
   */
  private void requireKey(String key) {
    if (key == null || !key.matches("^[a-z][A-Za-z0-9-]*$")) {
      throw new IllegalArgumentException(
          "A key starts with a lower-case letter and continues with letters, digits or hyphens. "
              + "It is stored in every item that uses it, so it is not a label.");
    }
  }

  /**
   * Refuses a field whose kind and value list disagree.
   *
   * @param command the field
   * @throws IllegalArgumentException when an enumeration names no list, or a non-enumeration does
   */
  private void requireFieldShape(FieldCommand command) {
    if (command.labels() == null || command.labels().isEmpty()) {
      throw new IllegalArgumentException(
          "A field needs a label in at least one language: nothing else can name it on a form.");
    }
    if (command.dataType().needsValueList() != (command.valueListId() != null)) {
      throw new IllegalArgumentException(
          command.dataType().needsValueList()
              ? "A field of kind '" + command.dataType().token() + "' draws its values from a value "
                  + "list and must name one."
              : "Only 'enum' and 'multi-enum' draw on a value list; a list on any other kind would "
                  + "read as meaning something and mean nothing.");
    }
  }

  /**
   * A map as a JSON object, for a {@code jsonb} column.
   *
   * @param values the map, possibly {@code null}
   * @return the JSON text, {@code {}} for nothing
   */
  private String json(Map<String, String> values) {
    return mapper.writeValueAsString(values == null ? Map.of() : values);
  }

  /**
   * A JSON object of strings as a map.
   *
   * @param text the column value
   * @return the map, empty when there was nothing
   */
  private Map<String, String> labels(String text) {
    if (text == null || text.isBlank()) {
      return Map.of();
    }
    Map<String, String> values = new LinkedHashMap<>();
    mapper.readTree(text).properties()
        .forEach(entry -> values.put(entry.getKey(), entry.getValue().asString()));
    return Map.copyOf(values);
  }

  /**
   * The {@code constraints} column for a command, or {@code null} when it declares none.
   *
   * @param command the field
   * @return the JSON text or {@code null}
   */
  private String constraintsJson(FieldCommand command) {
    if (command.constraints() == null || command.constraints().isEmpty()) {
      return null;
    }
    Map<String, Object> members = new LinkedHashMap<>();
    if (command.constraints().pattern() != null) {
      members.put("pattern", command.constraints().pattern());
    }
    if (command.constraints().min() != null) {
      members.put("min", command.constraints().min().toPlainString());
    }
    if (command.constraints().max() != null) {
      members.put("max", command.constraints().max().toPlainString());
    }
    if (command.constraints().minLength() != null) {
      members.put("minLength", command.constraints().minLength());
    }
    if (command.constraints().maxLength() != null) {
      members.put("maxLength", command.constraints().maxLength());
    }
    if (command.constraints().unit() != null) {
      members.put("unit", command.constraints().unit());
    }
    if (command.constraints().referenceKind() != null) {
      members.put("referenceKind", command.constraints().referenceKind().name());
    }
    return mapper.writeValueAsString(members);
  }

  /**
   * The {@code visibility} column for a command, or {@code null} when the field is always shown.
   *
   * @param command the field
   * @return the JSON text or {@code null}
   */
  private String visibilityJson(FieldCommand command) {
    if (command.visibility() == null) {
      return null;
    }
    Map<String, Object> rule = new LinkedHashMap<>();
    rule.put("field", command.visibility().field());
    rule.put("operator", command.visibility().operator().token());
    if (command.visibility().operator().needsValue() && command.visibility().value() != null) {
      rule.put("value", mapper.readTree(command.visibility().value()));
    }
    return mapper.writeValueAsString(rule);
  }

  /**
   * The schema of a version that declares no fields.
   *
   * @param versionId the version, which becomes the document's identifier
   * @return the JSON text
   */
  private String emptySchema(UUID versionId) {
    return schemas.generate(versionId, List.of(), valueListId -> List.of());
  }

  /**
   * Fields by key, for comparing a version against what it inherits.
   *
   * @param fields the definitions
   * @return key to definition
   */
  private Map<String, FieldDefinitionView> byKey(List<FieldDefinitionView> fields) {
    Map<String, FieldDefinitionView> byKey = new LinkedHashMap<>();
    fields.forEach(field -> byKey.put(field.key(), field));
    return byKey;
  }

  /**
   * A PostgreSQL array literal of uuids.
   *
   * @param ids the ids
   * @return {@code {a,b,c}}
   */
  private String array(List<UUID> ids) {
    List<String> text = new ArrayList<>();
    ids.forEach(id -> text.add(id.toString()));
    return "{" + String.join(",", text) + "}";
  }

  /**
   * The exception for something this tenant cannot see.
   *
   * @param what the kind of thing
   * @param id its identifier
   * @return the exception, ready to throw
   */
  private TypeRegistry.UnknownTypeException unknown(String what, UUID id) {
    return new TypeRegistry.UnknownTypeException(
        "This tenant has no " + what + " " + id + ".");
  }

  /**
   * A version's identity and state.
   *
   * @param id the version
   * @param ownerId the type or category it belongs to
   * @param number its position in the series
   * @param published whether it is frozen
   * @param category whether its owner is a location category
   */
  private record Version(UUID id, UUID ownerId, int number, boolean published, boolean category) {}
}
