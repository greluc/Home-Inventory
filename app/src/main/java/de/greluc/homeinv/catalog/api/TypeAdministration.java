/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.catalog.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Editing the type system while the system runs (REQ-CORE-020, ADR-0020).
 *
 * <h2>The editing model</h2>
 *
 * <p>A type or a category owns an ordered series of <b>versions</b>. A version is a draft until it
 * is published, and a draft is what fields are added to and changed on; publishing freezes it,
 * generates its JSON Schema, and makes it the version new items and locations are written against.
 * Anything already written keeps the version it named, which is the whole of REQ-CORE-025 — "a type
 * change devalues no existing items".
 *
 * <p>Editing a published version is therefore refused rather than silently branching: a tenant that
 * wants to change a published type asks for a new draft, which starts as a copy of what is published
 * now.
 *
 * <h2>What is deliberately absent</h2>
 *
 * <p>There is no operation that deletes a type, and none that deletes a field without first showing
 * what it would destroy. ADR-0020 keeps the values of a field that is no longer wanted and hides the
 * field instead; the one operation that truly removes them is
 * {@link #removeField(UUID, UUID)}, and {@link #previewFieldRemoval(UUID)} is what a person is shown
 * before it (REQ-CORE-026).
 */
public interface TypeAdministration {

  /**
   * Creates an item type with an empty draft version.
   *
   * @param command the key, kind and optional parent
   * @param actor the authenticated user, recorded in the audit columns
   * @return the type, with the id of the draft that was created with it
   * @throws TypeKeyTakenException when the tenant already has a type with that key
   * @throws TypeRegistry.UnknownTypeException when a named parent does not exist
   */
  ItemTypeView createItemType(CreateItemTypeCommand command, UUID actor);

  /**
   * One page of the tenant's item types, archived ones included.
   *
   * <p>Paged like every other collection (REQ-NFR-010). A tenant with three types gets all three on
   * the first page; a tenant that has been generating types from an import gets a bounded answer
   * rather than a slow one.
   *
   * @param cursor an opaque cursor from a previous page, or {@code null} for the first
   * @param limit how many at most; capped at 200
   * @return the page and a cursor for the next one
   */
  ItemTypePage itemTypes(String cursor, int limit);

  /**
   * Archives an item type so it is offered for nothing new.
   *
   * <p>Archived and not deleted: items reference its versions, and a type whose versions vanished
   * would leave them pointing at nothing. The built-in type cannot be archived — it is what an item
   * created without a chosen type is written against.
   *
   * @param typeId the type
   * @param actor the authenticated user
   * @return the archived type
   * @throws TypeRegistry.UnknownTypeException when the tenant has no such type
   * @throws IllegalStateException when the type is the built-in one
   */
  ItemTypeView archiveItemType(UUID typeId, UUID actor);

  /**
   * Creates a location category with an empty draft version.
   *
   * @param command the key, labels and whether its locations travel with their contents
   * @param actor the authenticated user
   * @return the category, with the id of its draft
   * @throws TypeKeyTakenException when the tenant already has a category with that key
   */
  CategoryView createCategory(CreateCategoryCommand command, UUID actor);

  /**
   * One page of the tenant's location categories, archived ones included.
   *
   * @param cursor an opaque cursor from a previous page, or {@code null} for the first
   * @param limit how many at most; capped at 200
   * @return the page and a cursor for the next one
   */
  CategoryPage categories(String cursor, int limit);

  /**
   * Archives a location category.
   *
   * @param categoryId the category
   * @param actor the authenticated user
   * @return the archived category
   * @throws TypeRegistry.UnknownTypeException when the tenant has no such category
   */
  CategoryView archiveCategory(UUID categoryId, UUID actor);

  /**
   * Starts a new draft of a type or a category, copying what is published now.
   *
   * <p>A copy rather than an empty version: a new version of "book" that lost every field would be a
   * new type wearing the same name, and the tenant asking for it wants to change one thing.
   *
   * @param ownerId the item type or the location category
   * @param actor the authenticated user
   * @return the draft
   * @throws TypeRegistry.UnknownTypeException when the tenant has no such type or category
   * @throws IllegalStateException when a draft already exists — there is one at a time, because two
   *     would be two answers to "what is being edited"
   */
  VersionView draftVersion(UUID ownerId, UUID actor);

  /**
   * Reads one version with its fields.
   *
   * @param versionId the version
   * @return the version and every field it declares, in display order
   * @throws TypeRegistry.UnknownTypeException when the tenant has no such version
   */
  VersionView version(UUID versionId);

  /**
   * Publishes a draft: materialises inherited fields, generates the schema, freezes it.
   *
   * <p>Three things happen in one transaction, and the order matters. The fields of the parent chain
   * are copied in where this version does not override them (REQ-CORE-024, and the copy is what
   * makes a version a snapshot); every override is checked to be no looser than what it overrides;
   * the schema is generated from the result and stored on the version.
   *
   * @param versionId the draft
   * @param actor the authenticated user
   * @return the published version, with its materialised field set
   * @throws TypeRegistry.UnknownTypeException when the tenant has no such version
   * @throws VersionFrozenException when it is published already
   * @throws ConstraintLoosenedException when a field is looser than the one it inherits
   */
  VersionView publish(UUID versionId, UUID actor);

  /**
   * Adds a field to a draft.
   *
   * @param versionId the draft
   * @param command the field
   * @param actor the authenticated user
   * @return the new field
   * @throws TypeRegistry.UnknownTypeException when the tenant has no such version
   * @throws VersionFrozenException when the version is published
   * @throws TypeKeyTakenException when the version already declares that key
   */
  FieldDefinitionView addField(UUID versionId, FieldCommand command, UUID actor);

  /**
   * Changes a field of a draft.
   *
   * @param fieldId the field
   * @param command the new state; the key may not change, because items already carry it
   * @param actor the authenticated user
   * @return the changed field
   * @throws TypeRegistry.UnknownTypeException when the tenant has no such field
   * @throws VersionFrozenException when the field belongs to a published version
   */
  FieldDefinitionView updateField(UUID fieldId, FieldCommand command, UUID actor);

  /**
   * Deprecates a field: hidden from input, its values kept (REQ-CORE-026).
   *
   * <p>A draft operation like every other change, and deliberately so. Deprecating a field changes
   * what its version demands — a required field that is hidden is no longer required — and a
   * published version whose schema could change is not frozen. The tenant deprecates on a draft and
   * publishes it, which is one step more and keeps the one property everything else rests on.
   *
   * @param fieldId the field
   * @param actor the authenticated user
   * @throws TypeRegistry.UnknownTypeException when the tenant has no such field
   * @throws VersionFrozenException when the field belongs to a published version
   */
  void deprecateField(UUID fieldId, UUID actor);

  /**
   * What removing a field would destroy.
   *
   * @param fieldId the field
   * @return the field's key and how many items or locations carry a value for it
   * @throws TypeRegistry.UnknownTypeException when the tenant has no such field
   */
  FieldRemovalPreview previewFieldRemoval(UUID fieldId);

  /**
   * Removes a deprecated field and every value stored under it.
   *
   * <p>The one destructive operation in this interface, and the reason
   * {@code catalog:type:delete} is a permission of its own. Refused unless the field is deprecated
   * first: REQ-CORE-026 makes hiding the step before destroying, so that the values survive a change
   * of mind.
   *
   * @param fieldId the field
   * @param actor the authenticated user
   * @throws TypeRegistry.UnknownTypeException when the tenant has no such field
   * @throws IllegalStateException when the field is not deprecated
   */
  void removeField(UUID fieldId, UUID actor);

  /**
   * Creates a value list.
   *
   * @param command the key and labels
   * @param actor the authenticated user
   * @return the empty list
   * @throws TypeKeyTakenException when the tenant already has a list with that key
   */
  ValueListView createValueList(CreateValueListCommand command, UUID actor);

  /**
   * One page of the tenant's value lists, each with its entries.
   *
   * <p>The entries are not paged separately. A value list a person can choose from on a form is
   * short by construction — a list of two hundred conditions is not a list, it is a search — and
   * splitting them would make a form render in two requests.
   *
   * @param cursor an opaque cursor from a previous page, or {@code null} for the first
   * @param limit how many lists at most; capped at 200
   * @return the page and a cursor for the next one
   */
  ValueListPage valueLists(String cursor, int limit);

  /**
   * Adds an entry to a value list.
   *
   * @param valueListId the list
   * @param command the value and its labels
   * @param actor the authenticated user
   * @return the entry
   * @throws TypeRegistry.UnknownTypeException when the tenant has no such list
   * @throws TypeKeyTakenException when the list already holds that value
   */
  ValueListEntryView addValueListEntry(UUID valueListId, AddEntryCommand command, UUID actor);

  /**
   * Archives a value list entry: not offered for new input, still valid where it is stored.
   *
   * @param entryId the entry
   * @param actor the authenticated user
   * @throws TypeRegistry.UnknownTypeException when the tenant has no such entry
   */
  void archiveValueListEntry(UUID entryId, UUID actor);

  /**
   * What to call a new item type and where it sits.
   *
   * @param key the stable key, unique per tenant — {@code book}, {@code power-tool}
   * @param kind whether its items exist physically
   * @param parentId the type it inherits from, or {@code null}
   * @param icon an icon name for the client, or {@code null}
   */
  record CreateItemTypeCommand(String key, TypeKind kind, UUID parentId, String icon) {}

  /**
   * What to call a new location category.
   *
   * @param key the stable key, unique per tenant
   * @param labels the name per language tag, as the tenant wrote it
   * @param mobile whether its locations travel with their contents (REQ-CORE-043)
   */
  record CreateCategoryCommand(String key, Map<String, String> labels, boolean mobile) {}

  /**
   * A field, as it is added or changed.
   *
   * @param key the attribute key; ignored when changing, because items already carry it
   * @param dataType the kind of value
   * @param labels the field's name per language tag; at least one is required
   * @param helpTexts the explanatory line per language tag, possibly empty
   * @param required whether a value must be present
   * @param defaultValue the JSON text of the starting value, or {@code null}
   * @param constraints the declarative limits, or {@code null} for none
   * @param valueListId the list an enumeration draws from; required for one, refused otherwise
   * @param visibility the one condition that decides whether a client shows it, or {@code null}
   * @param group the form section, or {@code null}
   * @param displayOrder the position within the group
   * @param searchable whether it is mirrored for filtering
   * @param sortable whether it is mirrored for ordering
   * @param facetable whether it is mirrored for counting
   * @param sensitive whether reading it needs a permission of its own (ADR-0019)
   */
  record FieldCommand(
      String key,
      FieldDataType dataType,
      Map<String, String> labels,
      Map<String, String> helpTexts,
      boolean required,
      String defaultValue,
      FieldConstraints constraints,
      UUID valueListId,
      VisibilityRule visibility,
      String group,
      int displayOrder,
      boolean searchable,
      boolean sortable,
      boolean facetable,
      boolean sensitive) {}

  /**
   * What to call a new value list.
   *
   * @param key the stable key, unique per tenant
   * @param labels the list's name per language tag
   */
  record CreateValueListCommand(String key, Map<String, String> labels) {}

  /**
   * A value to add to a list.
   *
   * @param value what lands in an item's attributes; stable, because relabelling must not rewrite
   *     every item that chose it
   * @param labels the readable form per language tag
   * @param displayOrder the position in the list
   */
  record AddEntryCommand(String value, Map<String, String> labels, int displayOrder) {}

  /**
   * An item type as an administrator sees it.
   *
   * @param id the type
   * @param key its stable key
   * @param kind whether its items exist physically
   * @param parentId the type it inherits from, or {@code null}
   * @param icon the icon name, or {@code null}
   * @param builtin whether it is the one every tenant is provisioned with
   * @param archived whether it is offered for nothing new
   * @param publishedVersionId the newest published version, or {@code null} when none is published
   * @param draftVersionId the version being edited, or {@code null} when none is
   */
  record ItemTypeView(
      UUID id,
      String key,
      TypeKind kind,
      UUID parentId,
      String icon,
      boolean builtin,
      boolean archived,
      UUID publishedVersionId,
      UUID draftVersionId) {}

  /**
   * A location category as an administrator sees it.
   *
   * @param id the category
   * @param key its stable key
   * @param labels its name per language tag
   * @param mobile whether its locations travel with their contents
   * @param builtin whether it was provisioned with the tenant
   * @param archived whether it is offered for nothing new
   * @param publishedVersionId the newest published version, or {@code null}
   * @param draftVersionId the version being edited, or {@code null}
   */
  record CategoryView(
      UUID id,
      String key,
      Map<String, String> labels,
      boolean mobile,
      boolean builtin,
      boolean archived,
      UUID publishedVersionId,
      UUID draftVersionId) {}

  /**
   * One version and the fields it declares.
   *
   * @param id the version
   * @param ownerId the item type or location category it belongs to
   * @param versionNumber its position in that owner's series, starting at one
   * @param published whether it is frozen and referenceable
   * @param fields every field, in display order, inherited ones included once published
   */
  record VersionView(
      UUID id, UUID ownerId, int versionNumber, boolean published, List<FieldDefinitionView> fields) {}

  /**
   * What a field removal would destroy (REQ-CORE-026).
   *
   * @param fieldId the field
   * @param key its attribute key
   * @param affected how many items or locations carry a value under that key today
   */
  record FieldRemovalPreview(UUID fieldId, String key, long affected) {}

  /**
   * A value list with its entries.
   *
   * @param id the list
   * @param key its stable key
   * @param labels its name per language tag
   * @param entries its entries in display order, archived ones included
   */
  record ValueListView(
      UUID id, String key, Map<String, String> labels, List<ValueListEntryView> entries) {}

  /**
   * One permitted value.
   *
   * @param id the entry
   * @param value what is stored in an item's attributes
   * @param labels its readable form per language tag
   * @param displayOrder its position
   * @param archived whether it is still offered for new input
   */
  record ValueListEntryView(
      UUID id, String value, Map<String, String> labels, int displayOrder, boolean archived) {}

  /**
   * One page of item types.
   *
   * @param items the types on this page
   * @param nextCursor the cursor for the next page, or {@code null} when this was the last
   */
  record ItemTypePage(List<ItemTypeView> items, String nextCursor) {}

  /**
   * One page of location categories.
   *
   * @param items the categories on this page
   * @param nextCursor the cursor for the next page, or {@code null} when this was the last
   */
  record CategoryPage(List<CategoryView> items, String nextCursor) {}

  /**
   * One page of value lists.
   *
   * @param items the lists on this page, each with its entries
   * @param nextCursor the cursor for the next page, or {@code null} when this was the last
   */
  record ValueListPage(List<ValueListView> items, String nextCursor) {}

  /** Thrown when a key a tenant chose is already in use for the same kind of thing. */
  class TypeKeyTakenException extends RuntimeException {

    private final String key;

    /**
     * Names the key, which is what the caller sent and what they have to change.
     *
     * @param what the kind of thing — a type, a category, a field, a value
     * @param key the key that is taken
     */
    public TypeKeyTakenException(String what, String key) {
      super("This tenant already has a " + what + " called '" + key + "'.");
      this.key = key;
    }

    /**
     * The key that is already in use.
     *
     * @return the key
     */
    public String getKey() {
      return key;
    }
  }

  /** Thrown when a published version is edited. A published version is a snapshot, not a draft. */
  class VersionFrozenException extends RuntimeException {

    private final UUID versionId;

    /**
     * Names the version, so a client can offer to start a draft from it.
     *
     * @param versionId the published version
     */
    public VersionFrozenException(UUID versionId) {
      super(
          "Version "
              + versionId
              + " is published and cannot be changed. Start a new draft from it instead.");
      this.versionId = versionId;
    }

    /**
     * The version that is frozen.
     *
     * @return the version id
     */
    public UUID getVersionId() {
      return versionId;
    }
  }

  /** Thrown when a type widens what it inherits, which REQ-CORE-024 permits in one direction only. */
  class ConstraintLoosenedException extends RuntimeException {

    private final String fieldKey;

    /**
     * Names the field and what was loosened about it.
     *
     * @param fieldKey the field's attribute key
     * @param what the property that was widened, for example {@code required} or {@code max}
     */
    public ConstraintLoosenedException(String fieldKey, String what) {
      super(
          "Field '"
              + fieldKey
              + "' loosens "
              + what
              + " compared with the type it inherits from. An inheriting type may tighten a field "
              + "and may not widen it.");
      this.fieldKey = fieldKey;
    }

    /**
     * The field that loosens something.
     *
     * @return its attribute key
     */
    public String getFieldKey() {
      return fieldKey;
    }
  }
}
