/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.homeinv.catalog.api.FieldConstraints;
import de.greluc.homeinv.catalog.api.FieldDataType;
import de.greluc.homeinv.catalog.api.FieldDefinitionView;
import de.greluc.homeinv.catalog.api.LocationCategories;
import de.greluc.homeinv.catalog.api.LocationCategoryView;
import de.greluc.homeinv.catalog.api.TypeAdministration;
import de.greluc.homeinv.catalog.api.TypeKind;
import de.greluc.homeinv.catalog.api.TypeRegistry;
import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The type system a tenant edits while the system runs (REQ-CORE-020…029, REQ-CORE-041).
 *
 * <p>Driven through the service rather than through HTTP: what is under test is the editing model —
 * drafts, publication, inheritance, the refusal to widen — and the REST layer over it is an adapter.
 * Against a real PostgreSQL, because the constraints that hold half of this together are in the
 * schema: exactly one owner per field, a value list only for enumerations, the key shape.
 */
@DisplayName("The type editor")
class TypeEditorIT extends AbstractIntegrationTest {

  @Autowired private TypeAdministration types;
  @Autowired private TypeRegistry registry;
  @Autowired private LocationCategories categories;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private AppUserRepository users;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;

  @Test
  @DisplayName("creates a type with a draft, and publishes it into a schema (REQ-CORE-020, 027)")
  void createAddPublish() {
    Tenant tenant = newTenant("type-editor-editor@example.org");
    inTenant(
        tenant,
        () -> {
          TypeAdministration.ItemTypeView book =
              types.createItemType(
                  new TypeAdministration.CreateItemTypeCommand(
                      "book", TypeKind.PHYSICAL, null, null),
                  tenant.userId());
          assertThat(book.draftVersionId()).isNotNull();
          assertThat(book.publishedVersionId()).isNull();

          types.addField(
              book.draftVersionId(),
              field("isbn", FieldDataType.TEXT, true, FieldConstraints.NONE),
              tenant.userId());

          TypeAdministration.VersionView published =
              types.publish(book.draftVersionId(), tenant.userId());
          assertThat(published.published()).isTrue();
          assertThat(published.fields()).extracting(FieldDefinitionView::key).containsExactly("isbn");

          // The document is what a client validates against offline (ADR-0056), so
          // it has to carry the field and the fact that it is demanded.
          String schema = registry.jsonSchema(published.id());
          assertThat(schema).contains("\"isbn\"").contains("\"required\"");

          // And the type now answers "which version do new items reference".
          assertThat(registry.publishedItemTypeVersion(book.id())).isEqualTo(published.id());
        });
  }

  @Test
  @DisplayName("lists what may be queried, and leaves out a key two types disagree about")
  void theQueryableAllowlist() {
    Tenant tenant = newTenant("type-editor-queryable@example.org");
    inTenant(
        tenant,
        () -> {
          // `model` is text on the tool and an integer on the appliance. The same
          // key in two storage classes lives in two columns of
          // `item_attr_index`, so no single predicate over it means anything.
          TypeAdministration.ItemTypeView tool = newType(tenant, "queryable-tool");
          types.addField(
              tool.draftVersionId(),
              queryable("manufacturer", FieldDataType.TEXT, true, true, true),
              tenant.userId());
          types.addField(
              tool.draftVersionId(),
              queryable("model", FieldDataType.TEXT, true, false, false),
              tenant.userId());
          types.addField(
              tool.draftVersionId(),
              queryable("power", FieldDataType.QUANTITY, true, true, false),
              tenant.userId());
          types.publish(tool.draftVersionId(), tenant.userId());

          TypeAdministration.ItemTypeView appliance = newType(tenant, "queryable-appliance");
          types.addField(
              appliance.draftVersionId(),
              queryable("model", FieldDataType.INTEGER, true, false, false),
              tenant.userId());
          types.publish(appliance.draftVersionId(), tenant.userId());

          var queryable = registry.queryableFields();
          assertThat(queryable).extracting(TypeRegistry.QueryableField::key)
              .as("a key its types disagree about is left out rather than guessed at")
              .contains("manufacturer", "power")
              .doesNotContain("model");

          // The flags are the union across the published versions, because a
          // query spans types: a key sortable anywhere is sortable.
          assertThat(queryable)
              .filteredOn(field -> field.key().equals("manufacturer"))
              .singleElement()
              .satisfies(
                  field -> {
                    assertThat(field.filterable()).isTrue();
                    assertThat(field.sortable()).isTrue();
                    assertThat(field.facetable()).isTrue();
                  });

          // And the data type comes with it, because it decides which column a
          // predicate reads -- and whether a comparison has to name a unit.
          assertThat(queryable)
              .filteredOn(field -> field.key().equals("power"))
              .singleElement()
              .satisfies(
                  field -> {
                    assertThat(field.dataType()).isEqualTo(FieldDataType.QUANTITY);
                    assertThat(field.dataType().carriesUnit())
                        .as("a quantity is compared within its unit, never across")
                        .isTrue();
                  });
        });
  }

  @Test
  @DisplayName("refuses a second type with the same key (REQ-CORE-020)")
  void keysAreUniquePerTenant() {
    Tenant tenant = newTenant("type-editor-keys@example.org");
    inTenant(
        tenant,
        () -> {
          types.createItemType(
              new TypeAdministration.CreateItemTypeCommand("tool", TypeKind.PHYSICAL, null, null),
              tenant.userId());
          assertThatThrownBy(
                  () ->
                      types.createItemType(
                          new TypeAdministration.CreateItemTypeCommand(
                              "tool", TypeKind.PHYSICAL, null, null),
                          tenant.userId()))
              .isInstanceOf(TypeAdministration.TypeKeyTakenException.class);
        });
  }

  @Test
  @DisplayName("refuses every edit to a published version (REQ-CORE-025)")
  void publishedVersionsAreFrozen() {
    Tenant tenant = newTenant("type-editor-frozen@example.org");
    inTenant(
        tenant,
        () -> {
          UUID draft = newType(tenant, "frozen").draftVersionId();
          types.publish(draft, tenant.userId());

          assertThatThrownBy(
                  () ->
                      types.addField(
                          draft,
                          field("late", FieldDataType.TEXT, false, FieldConstraints.NONE),
                          tenant.userId()))
              .isInstanceOf(TypeAdministration.VersionFrozenException.class);
          assertThatThrownBy(() -> types.publish(draft, tenant.userId()))
              .isInstanceOf(TypeAdministration.VersionFrozenException.class);
        });
  }

  @Test
  @DisplayName("starts the next draft as a copy of what is published (REQ-CORE-025)")
  void draftCopiesThePublishedVersion() {
    Tenant tenant = newTenant("type-editor-copy@example.org");
    inTenant(
        tenant,
        () -> {
          TypeAdministration.ItemTypeView type = newType(tenant, "appliance");
          types.addField(
              type.draftVersionId(),
              field("serial", FieldDataType.TEXT, false, FieldConstraints.NONE),
              tenant.userId());
          types.publish(type.draftVersionId(), tenant.userId());

          TypeAdministration.VersionView second = types.draftVersion(type.id(), tenant.userId());
          assertThat(second.versionNumber()).isEqualTo(2);
          assertThat(second.published()).isFalse();
          assertThat(second.fields()).extracting(FieldDefinitionView::key).containsExactly("serial");

          // One draft at a time: two would be two answers to what is being edited.
          assertThatThrownBy(() -> types.draftVersion(type.id(), tenant.userId()))
              .isInstanceOf(IllegalStateException.class);
        });
  }

  @Test
  @DisplayName("materialises what a type inherits and refuses what it widens (REQ-CORE-024)")
  void inheritanceTightensOnly() {
    Tenant tenant = newTenant("type-editor-inherit@example.org");
    inTenant(
        tenant,
        () -> {
          TypeAdministration.ItemTypeView medium = newType(tenant, "medium");
          types.addField(
              medium.draftVersionId(),
              field("title", FieldDataType.TEXT, true, FieldConstraints.NONE),
              tenant.userId());
          types.addField(
              medium.draftVersionId(),
              field(
                  "pages",
                  FieldDataType.INTEGER,
                  false,
                  new FieldConstraints(null, BigDecimal.ONE, BigDecimal.valueOf(1000), null, null, null, null)),
              tenant.userId());
          types.publish(medium.draftVersionId(), tenant.userId());

          TypeAdministration.ItemTypeView novel =
              types.createItemType(
                  new TypeAdministration.CreateItemTypeCommand(
                      "novel", TypeKind.PHYSICAL, medium.id(), null),
                  tenant.userId());

          // Widening the inherited bound: 2000 pages where the parent allowed 1000.
          types.addField(
              novel.draftVersionId(),
              field(
                  "pages",
                  FieldDataType.INTEGER,
                  false,
                  new FieldConstraints(null, BigDecimal.ONE, BigDecimal.valueOf(2000), null, null, null, null)),
              tenant.userId());
          assertThatThrownBy(() -> types.publish(novel.draftVersionId(), tenant.userId()))
              .isInstanceOf(TypeAdministration.ConstraintLoosenedException.class);

          // Tightened instead: 500 pages, inside what the parent allows.
          List<FieldDefinitionView> draftFields = types.version(novel.draftVersionId()).fields();
          UUID pages =
              draftFields.stream()
                  .filter(candidate -> candidate.key().equals("pages"))
                  .findFirst()
                  .orElseThrow()
                  .id();
          types.updateField(
              pages,
              field(
                  "pages",
                  FieldDataType.INTEGER,
                  false,
                  new FieldConstraints(null, BigDecimal.ONE, BigDecimal.valueOf(500), null, null, null, null)),
              tenant.userId());

          TypeAdministration.VersionView published =
              types.publish(novel.draftVersionId(), tenant.userId());

          // `title` was never declared here and is present anyway: publishing copies
          // the parent's fields in, which is what makes a version a snapshot.
          assertThat(published.fields())
              .extracting(FieldDefinitionView::key)
              .containsExactlyInAnyOrder("title", "pages");
          assertThat(registry.jsonSchema(published.id())).contains("\"title\"");
        });
  }

  @Test
  @DisplayName("hides a field and keeps its values, then destroys them only when asked (REQ-CORE-026)")
  void deprecateThenRemove() {
    Tenant tenant = newTenant("type-editor-deprecate@example.org");
    inTenant(
        tenant,
        () -> {
          TypeAdministration.ItemTypeView type = newType(tenant, "clothing");
          FieldDefinitionView size =
              types.addField(
                  type.draftVersionId(),
                  field("size", FieldDataType.TEXT, true, FieldConstraints.NONE),
                  tenant.userId());
          types.deprecateField(size.id(), tenant.userId());
          types.publish(type.draftVersionId(), tenant.userId());

          FieldDefinitionView after =
              types.version(type.draftVersionId()).fields().stream()
                  .filter(candidate -> candidate.key().equals("size"))
                  .findFirst()
                  .orElseThrow();
          assertThat(after.deprecated()).isTrue();
          // Declared and no longer demanded: the values of a hidden field stay
          // valid, and a new item is not asked for one.
          assertThat(after.effectivelyRequired()).isFalse();
          assertThat(registry.jsonSchema(type.draftVersionId())).contains("\"size\"");

          TypeAdministration.FieldRemovalPreview preview = types.previewFieldRemoval(after.id());
          assertThat(preview.key()).isEqualTo("size");
          assertThat(preview.affected()).isZero();

          types.removeField(after.id(), tenant.userId());
          assertThat(types.version(type.draftVersionId()).fields()).isEmpty();
          assertThat(registry.jsonSchema(type.draftVersionId())).doesNotContain("\"size\"");
        });
  }

  @Test
  @DisplayName("refuses to remove a field that was not hidden first (REQ-CORE-026)")
  void removalNeedsDeprecationFirst() {
    Tenant tenant = newTenant("type-editor-straight-to-removal@example.org");
    inTenant(
        tenant,
        () -> {
          TypeAdministration.ItemTypeView type = newType(tenant, "furniture");
          FieldDefinitionView material =
              types.addField(
                  type.draftVersionId(),
                  field("material", FieldDataType.TEXT, false, FieldConstraints.NONE),
                  tenant.userId());
          assertThatThrownBy(() -> types.removeField(material.id(), tenant.userId()))
              .isInstanceOf(IllegalStateException.class);
        });
  }

  @Test
  @DisplayName("draws an enumeration from a value list, reusable across types (REQ-CORE-029)")
  void valueLists() {
    Tenant tenant = newTenant("type-editor-lists@example.org");
    inTenant(
        tenant,
        () -> {
          TypeAdministration.ValueListView condition =
              types.createValueList(
                  new TypeAdministration.CreateValueListCommand(
                      "condition", Map.of("en", "Condition")),
                  tenant.userId());
          types.addValueListEntry(
              condition.id(),
              new TypeAdministration.AddEntryCommand("new", Map.of("en", "New"), 0),
              tenant.userId());
          types.addValueListEntry(
              condition.id(),
              new TypeAdministration.AddEntryCommand("used", Map.of("en", "Used"), 1),
              tenant.userId());

          assertThat(registry.valueListEntries(condition.id())).containsExactly("new", "used");

          // The same list in two types, which is the whole of REQ-CORE-029.
          for (String key : List.of("bike", "camera")) {
            TypeAdministration.ItemTypeView type = newType(tenant, key);
            types.addField(
                type.draftVersionId(),
                new TypeAdministration.FieldCommand(
                    "condition",
                    FieldDataType.ENUM,
                    Map.of("en", "Condition"),
                    Map.of(),
                    false,
                    null,
                    FieldConstraints.NONE,
                    condition.id(),
                    null,
                    null,
                    0,
                    true,
                    false,
                    true,
                    false),
                tenant.userId());
            TypeAdministration.VersionView published =
                types.publish(type.draftVersionId(), tenant.userId());
            assertThat(registry.jsonSchema(published.id())).contains("\"new\"").contains("\"used\"");
          }

          // An archived entry stays valid where it is stored and is offered no more.
          UUID used =
              types.valueLists(null, 50).data().stream()
                  .filter(list -> list.key().equals("condition"))
                  .findFirst()
                  .orElseThrow()
                  .entries()
                  .stream()
                  .filter(entry -> entry.value().equals("used"))
                  .findFirst()
                  .orElseThrow()
                  .id();
          types.archiveValueListEntry(used, tenant.userId());
          assertThat(registry.valueListEntries(condition.id())).containsExactly("new");
        });
  }

  @Test
  @DisplayName("gives a location category its own fields (REQ-CORE-041)")
  void categoriesCarryFields() {
    Tenant tenant = newTenant("type-editor-categories@example.org");
    inTenant(
        tenant,
        () -> {
          TypeAdministration.CategoryView movingBox =
              types.createCategory(
                  new TypeAdministration.CreateCategoryCommand(
                      "packing-crate", Map.of("en", "Packing crate", "de", "Umzugskiste"), true),
                  tenant.userId());
          assertThat(movingBox.mobile()).isTrue();

          types.addField(
              movingBox.draftVersionId(),
              field("targetRoom", FieldDataType.TEXT, false, FieldConstraints.NONE),
              tenant.userId());
          types.addField(
              movingBox.draftVersionId(),
              field("packedOn", FieldDataType.DATE, false, FieldConstraints.NONE),
              tenant.userId());
          types.addField(
              movingBox.draftVersionId(),
              field("sealNumber", FieldDataType.TEXT, false, FieldConstraints.NONE),
              tenant.userId());

          TypeAdministration.VersionView published =
              types.publish(movingBox.draftVersionId(), tenant.userId());
          assertThat(published.fields())
              .extracting(FieldDefinitionView::key)
              .containsExactlyInAnyOrder("targetRoom", "packedOn", "sealNumber");
          assertThat(registry.publishedCategoryVersion(movingBox.id())).isEqualTo(published.id());
        });
  }

  @Test
  @DisplayName("renames a shipped category, and the picker answers with the tenant's own name")
  void everyCategoryIsEditable() {
    Tenant tenant = newTenant("type-editor-editable@example.org");
    inTenant(
        tenant,
        () -> {
          // REQ-CORE-042 asks for the thirteen to be "all present AND editable",
          // and a shipped category is one this instance seeded rather than one it
          // owns: a tenant calling its `room` "Zimmer" is naming its own tree.
          TypeAdministration.CategoryView room =
              types.categories(null, 200).data().stream()
                  .filter(view -> "room".equals(view.key()))
                  .findFirst()
                  .orElseThrow();
          assertThat(room.builtin()).isTrue();
          assertThat(room.labels()).isEmpty();
          assertThat(room.mobile()).isFalse();

          TypeAdministration.CategoryView renamed =
              types.updateCategory(
                  room.id(),
                  new TypeAdministration.UpdateCategoryCommand(
                      Map.of("en", "Room", "de", "Zimmer"), "door-open", false),
                  tenant.userId());

          // The key does not move. It is what a client translates, what an export
          // writes down, and what makes the rename additive rather than a new
          // category wearing an old id.
          assertThat(renamed.key()).isEqualTo("room");
          assertThat(renamed.labels()).containsEntry("de", "Zimmer").containsEntry("en", "Room");
          assertThat(renamed.icon()).isEqualTo("door-open");

          // And the picker a client actually reads carries it, which is the half
          // that makes the edit visible to anybody (REQ-CORE-041).
          LocationCategoryView asOffered =
              categories.list(null, 200).data().stream()
                  .filter(view -> view.id().equals(room.id()))
                  .findFirst()
                  .orElseThrow();
          assertThat(asOffered.labels()).containsEntry("de", "Zimmer");
          assertThat(asOffered.icon()).isEqualTo("door-open");
          assertThat(asOffered.mobile()).isFalse();

          // Mobility is settable, which is what REQ-CORE-043's "can be marked
          // mobile" means: every shipped category ships stationary, and marking
          // one is the tenant's to do.
          assertThat(
                  types
                      .updateCategory(
                          room.id(),
                          new TypeAdministration.UpdateCategoryCommand(
                              Map.of("de", "Zimmer"), null, true),
                          tenant.userId())
                      .mobile())
              .isTrue();

          // Replaced whole, never merged: dropping a language is how a tenant
          // undoes a translation, and a merge offers no spelling for it.
          TypeAdministration.CategoryView narrowed =
              types.updateCategory(
                  room.id(),
                  new TypeAdministration.UpdateCategoryCommand(Map.of(), null, false),
                  tenant.userId());
          assertThat(narrowed.labels()).isEmpty();
          assertThat(narrowed.icon()).isNull();
        });
  }

  @Test
  @DisplayName("changes a type's icon and nothing that would reinterpret its items")
  void anItemTypeIsEditable() {
    Tenant tenant = newTenant("type-editor-icon@example.org");
    inTenant(
        tenant,
        () -> {
          TypeAdministration.ItemTypeView tool = newType(tenant, "power-tool");
          assertThat(tool.icon()).isNull();

          TypeAdministration.ItemTypeView withIcon =
              types.updateItemType(
                  tool.id(), new TypeAdministration.UpdateItemTypeCommand("drill"), tenant.userId());
          assertThat(withIcon.icon()).isEqualTo("drill");
          // The three that would reinterpret existing items are not in the
          // command at all, so there is nothing to assert about them beyond this:
          // they came back unchanged.
          assertThat(withIcon.key()).isEqualTo("power-tool");
          assertThat(withIcon.kind()).isEqualTo(TypeKind.PHYSICAL);
          assertThat(withIcon.parentId()).isNull();

          assertThat(
                  types
                      .updateItemType(
                          tool.id(),
                          new TypeAdministration.UpdateItemTypeCommand(null),
                          tenant.userId())
                      .icon())
              .isNull();
        });
  }

  @Test
  @DisplayName("refuses a field whose kind and value list disagree (REQ-CORE-022)")
  void enumerationsNeedAList() {
    Tenant tenant = newTenant("type-editor-shape@example.org");
    inTenant(
        tenant,
        () -> {
          UUID draft = newType(tenant, "shape").draftVersionId();
          assertThatThrownBy(
                  () ->
                      types.addField(
                          draft,
                          field("colour", FieldDataType.ENUM, false, FieldConstraints.NONE),
                          tenant.userId()))
              .isInstanceOf(IllegalArgumentException.class);
        });
  }

  /**
   * A field command with everything at its default.
   *
   * @param key the attribute key
   * @param type the kind of value
   * @param required whether a value is demanded
   * @param constraints the declared limits
   * @return the command
   */
  /**
   * A field that may be queried, with the three flags set as given.
   *
   * @param key the attribute key
   * @param type what it holds
   * @param filterable whether it is mirrored for filtering
   * @param sortable whether it is mirrored for ordering
   * @param facetable whether it is mirrored for counting
   * @return the command
   */
  private TypeAdministration.FieldCommand queryable(
      String key, FieldDataType type, boolean filterable, boolean sortable, boolean facetable) {
    return new TypeAdministration.FieldCommand(
        key,
        type,
        Map.of("en", key),
        Map.of(),
        false,
        null,
        FieldConstraints.NONE,
        null,
        null,
        null,
        0,
        filterable,
        sortable,
        facetable,
        false);
  }

  private TypeAdministration.FieldCommand field(
      String key, FieldDataType type, boolean required, FieldConstraints constraints) {
    return new TypeAdministration.FieldCommand(
        key,
        type,
        Map.of("en", key),
        Map.of(),
        required,
        null,
        constraints,
        null,
        null,
        null,
        0,
        false,
        false,
        false,
        false);
  }

  /**
   * A fresh item type with an empty draft.
   *
   * @param tenant whose type system
   * @param key the type's key
   * @return the type
   */
  private TypeAdministration.ItemTypeView newType(Tenant tenant, String key) {
    return types.createItemType(
        new TypeAdministration.CreateItemTypeCommand(key, TypeKind.PHYSICAL, null, null),
        tenant.userId());
  }

  /**
   * Runs a body inside one tenant's context, and in no transaction of its own.
   *
   * <p>Deliberately not wrapped in one. Every method under test is
   * {@code @Transactional} already, so a surrounding transaction would be joined rather than
   * nested — and half of these tests assert that a call is refused, which marks that joined
   * transaction rollback-only and fails the test at commit for a reason that has nothing to do with
   * what it checks. One transaction per call is also how the application is used.
   *
   * @param tenant the tenant
   * @param body what to run
   */
  private void inTenant(Tenant tenant, Runnable body) {
    TenantContext.runAs(tenant.tenantId(), body);
  }

  /**
   * A provisioned tenant with an owner.
   *
   * @param email the owner's address, which has to differ per test
   * @return the tenant and its owner
   */
  private Tenant newTenant(String email) {
    UUID userId = UUID.randomUUID();
    transactions.executeWithoutResult(
        status ->
            users.save(
                AppUser.create(
                    userId, email, "Test", "en", passwordEncoder.encode("irrelevant"), Instant.now())));
    return new Tenant(userId, provisioning.provision("Tenant of " + email, userId));
  }

  /**
   * The identifiers a test needs.
   *
   * @param userId the owner
   * @param tenantId the tenant
   */
  private record Tenant(UUID userId, UUID tenantId) {}
}
