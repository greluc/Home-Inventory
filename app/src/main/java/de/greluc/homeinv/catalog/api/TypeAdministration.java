/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.catalog.api;

import de.greluc.homeinv.platform.Page;
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
   * The item type templates this instance ships (REQ-CORE-030).
   *
   * <p>Eight of them, defined in {@code docs/reference/type-templates.yaml} and carried in the
   * artifact. The same eight for every tenant and every instance of this build, which is why the
   * answer takes no cursor: it is a property of the release rather than of the data.
   *
   * @return the templates, in the order the file lists them
   */
  TemplateList templates();

  /**
   * Creates a type from a template (REQ-CORE-030).
   *
   * <p>A copy, not a link. What comes out is an ordinary type with an ordinary published version,
   * and nothing afterwards remembers where it came from — a tenant renames what it likes, adds its
   * own fields and deprecates the rest, which is the "fully editable" half of the requirement. A
   * later change to the shipped file reaches no type already imported.
   *
   * @param templateKey which template
   * @param actor the authenticated user
   * @return the new type, with its published version
   * @throws TypeRegistry.UnknownTypeException when this build ships no such template
   * @throws TypeKeyTakenException when the tenant already has a type with that key — importing the
   *     same template twice is refused rather than producing {@code book-2}
   */
  ItemTypeView importTemplate(String templateKey, UUID actor);

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
  Page<TypeAdministration.ItemTypeView> itemTypes(String cursor, int limit);

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
  Page<TypeAdministration.CategoryView> categories(String cursor, int limit);

  /**
   * Changes an item type's icon (REQ-CORE-020).
   *
   * <p>The icon and nothing else. The key is the identifier, the kind decides whether an item of
   * the type has a location at all, and the parent decides which fields it inherits — each of the
   * three would reinterpret items that already exist, and a type change devalues no existing item
   * (REQ-CORE-025). What a type <em>declares</em> is edited through its draft version.
   *
   * @param typeId the type
   * @param command the new icon, or one naming {@code null} to remove it
   * @param actor the authenticated user
   * @return the type as it now stands
   * @throws TypeRegistry.UnknownTypeException when the tenant has no such type
   */
  ItemTypeView updateItemType(UUID typeId, UpdateItemTypeCommand command, UUID actor);

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
   * Changes what a location category is called and how it behaves (REQ-CORE-042).
   *
   * <p>Its name, its icon and whether its locations travel with their contents — not its key, which
   * is the identifier every client and every export writes down, and not its fields, which belong to
   * a version and change through one.
   *
   * <p>A shipped category is editable exactly like a tenant's own, which is what "all present and
   * editable" asks for. Renaming {@code room} to "Zimmer" is a tenant deciding what its own tree is
   * called; the key stays {@code room}, so a client that translates the shipped keys keeps working
   * and a tenant that overrides the labels wins over the translation.
   *
   * @param categoryId the category
   * @param command the new name, icon and mobility
   * @param actor the authenticated user
   * @return the category as it now stands
   * @throws TypeRegistry.UnknownTypeException when the tenant has no such category
   */
  CategoryView updateCategory(UUID categoryId, UpdateCategoryCommand command, UUID actor);

  /**
   * What a category takes underneath it (REQ-CORE-047).
   *
   * @param categoryId the category
   * @return the rule, whose list is empty when the category takes everything
   * @throws TypeRegistry.UnknownTypeException when the tenant has no such category
   */
  ChildCategoryRuleView childCategories(UUID categoryId);

  /**
   * Replaces what a category takes underneath it (REQ-CORE-047).
   *
   * <p>The whole set at once, not one entry at a time. A restriction is read as a whitelist, so
   * "which categories are permitted" has exactly one answer at any moment and a caller that sends it
   * whole cannot leave the rule half-changed. Sending an empty list withdraws the restriction and
   * the category takes everything again — that is the state a category starts in and the reason the
   * feature can be switched on in a tenant whose tree already exists.
   *
   * <p>Nothing is applied retroactively: locations that already sit where a new rule would forbid
   * stay there. The rule decides moves and new locations, and a tenant that tightens it is telling
   * the system what it wants next, not asking it to take the shelves out of the cupboard.
   *
   * @param categoryId the category the rule belongs to
   * @param permitted the categories it will take, in any order; duplicates are ignored
   * @param actor the authenticated user
   * @return the rule as it now stands
   * @throws TypeRegistry.UnknownTypeException when the tenant has no such category, or when one of
   *     the permitted ones is not a category of this tenant
   */
  ChildCategoryRuleView setChildCategories(UUID categoryId, List<UUID> permitted, UUID actor);

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
  Page<TypeAdministration.ValueListView> valueLists(String cursor, int limit);

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
   * One shipped template, as a picker shows it.
   *
   * @param key the template's key, which becomes the type's key on import
   * @param labels its name per language tag
   * @param kind whether its items exist physically
   * @param fieldCount how many fields it brings, so a person can tell a starting point from a
   *     catalogue before importing one
   */
  record TemplateView(String key, Map<String, String> labels, TypeKind kind, int fieldCount) {}

  /**
   * The templates this build ships.
   *
   * <p>No cursor and no page, and that is not an oversight: the set is fixed by the release, the
   * same for every tenant, and eight entries long. A cursor here would be a cursor over a constant.
   *
   * @param templates the templates, in the order the shipped file lists them
   */
  record TemplateList(List<TemplateView> templates) {}

  /**
   * What to call a new location category.
   *
   * @param key the stable key, unique per tenant
   * @param labels the name per language tag, as the tenant wrote it
   * @param mobile whether its locations travel with their contents (REQ-CORE-043)
   */
  record CreateCategoryCommand(String key, Map<String, String> labels, boolean mobile) {}

  /**
   * What may be changed about a location category.
   *
   * <p>Every field is replaced, none is merged: a caller that sends two labels means the category
   * has two, and a merge would make removing one impossible through an API that offers no way to
   * say "delete this language".
   *
   * @param labels the name per language tag, as the tenant wrote it; empty leaves the category
   *     nameless, which is the state every shipped one starts in and which makes a client fall back
   *     to translating the key
   * @param icon an icon name for the client, or {@code null} for none
   * @param mobile whether its locations travel with their contents (REQ-CORE-043)
   */
  record UpdateCategoryCommand(Map<String, String> labels, String icon, boolean mobile) {}

  /**
   * What may be changed about an item type.
   *
   * @param icon an icon name for the client, or {@code null} for none
   */
  record UpdateItemTypeCommand(String icon, Integer usefulLifeMonths) {

    /**
     * The command that changes only the icon.
     *
     * @param icon the icon, or null to clear it
     */
    public UpdateItemTypeCommand(String icon) {
      this(icon, null);
    }
  }

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
   * @param expiry whether this date is an <b>expiry</b>, and so belongs in the overview of
   *     REQ-LIFE-013. Only a {@code date} or {@code datetime} may carry it — a text field marked as
   *     an expiry would be a row in an overview sorted by due date with nothing to sort by, and the
   *     database refuses it
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
      boolean sensitive,
      boolean expiry) {

    /**
     * A field that is not an expiry.
     *
     * <p>Here so the callers written before REQ-LIFE-013 keep saying what they meant instead of
     * each gaining a {@code false}. Almost no field is an expiry: a type has one at most, and most
     * have none.
     *
     * @param key the stable key
     * @param dataType what it holds
     * @param labels the label per language
     * @param helpTexts the help text per language
     * @param required whether a value must be given
     * @param defaultValue the default, or {@code null}
     * @param constraints the bounds, or {@code null}
     * @param valueListId the list an enum draws from, or {@code null}
     * @param visibility when it is shown, or {@code null}
     * @param group the group it appears in, or {@code null}
     * @param displayOrder where it sits
     * @param searchable whether it is mirrored for filtering
     * @param sortable whether it is mirrored for ordering
     * @param facetable whether it is mirrored for counting
     * @param sensitive whether reading it needs a permission of its own
     */
    public FieldCommand(
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
        boolean sensitive) {
      this(
          key,
          dataType,
          labels,
          helpTexts,
          required,
          defaultValue,
          constraints,
          valueListId,
          visibility,
          group,
          displayOrder,
          searchable,
          sortable,
          facetable,
          sensitive,
          false);
    }
  }

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
      UUID draftVersionId,
      Integer usefulLifeMonths) {}

  /**
   * A location category as an administrator sees it.
   *
   * @param id the category
   * @param key its stable key
   * @param labels its name per language tag
   * @param icon an icon name for the client, or {@code null}
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
      String icon,
      boolean mobile,
      boolean builtin,
      boolean archived,
      UUID publishedVersionId,
      UUID draftVersionId) {}

  /**
   * What one category takes underneath it (REQ-CORE-047).
   *
   * <p>No cursor and no page, and that is deliberate: the list is the rule itself, and half a
   * whitelist is not a smaller answer but a wrong one. It cannot outgrow the tenant's categories,
   * which is a configuration surface a person maintains by hand — the write caps it at 200 anyway,
   * for the reason every collection here is capped.
   *
   * @param categoryId the category the rule belongs to
   * @param permitted the categories it takes, by id, ordered by when each was permitted. Empty
   *     means no restriction at all and therefore everything, not nothing
   */
  record ChildCategoryRuleView(UUID categoryId, List<UUID> permitted) {}

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
