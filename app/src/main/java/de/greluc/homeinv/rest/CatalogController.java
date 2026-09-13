/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import de.greluc.homeinv.authorization.api.Permission;
import de.greluc.homeinv.authorization.api.RequiresPermission;
import de.greluc.homeinv.catalog.api.FieldConstraints;
import de.greluc.homeinv.catalog.api.FieldDataType;
import de.greluc.homeinv.catalog.api.FieldDefinitionView;
import de.greluc.homeinv.catalog.api.TypeAdministration;
import de.greluc.homeinv.catalog.api.TypeKind;
import de.greluc.homeinv.catalog.api.TypeRegistry;
import de.greluc.homeinv.catalog.api.VisibilityRule;
import de.greluc.homeinv.identity.api.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The {@code /api/v1/catalog} endpoints: the type system a tenant edits while the system runs.
 *
 * <p>An adapter. Every rule is in {@link TypeAdministration}; what happens here is the translation
 * between JSON and the commands it takes — which is also why the request records below carry
 * <em>tokens</em> rather than Java enums. {@code multi-enum} and {@code notEquals} are the spellings
 * the database, the generated schema and the tenant export all use, and a client should not have to
 * know that one of them is called {@code MULTI_ENUM} inside a JVM.
 */
@RestController
@RequestMapping("/api/v1/catalog")
@RequiredArgsConstructor
public class CatalogController {

  private final TypeAdministration types;
  private final TypeRegistry registry;

  // -------------------------------------------------------------------------
  // Item types
  // -------------------------------------------------------------------------

  /**
   * One page of the tenant's item types.
   *
   * @param cursor an opaque cursor from a previous page, or omitted for the first
   * @param limit how many at most; capped at 200 by the service
   * @return the page and a cursor for the next one
   */
  @GetMapping(path = "/item-types", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.TYPE_READ)
  @CanFail(ProblemType.MALFORMED_REQUEST)
  public TypeAdministration.ItemTypePage itemTypes(
      @RequestParam(required = false) @Size(max = 500) String cursor,
      @RequestParam(required = false, defaultValue = "50") @Positive @Max(200) int limit) {
    return types.itemTypes(cursor, limit);
  }

  /**
   * Creates an item type, with an empty draft version to fill in.
   *
   * @param request the key, kind and optional parent
   * @param user the authenticated caller
   * @return the new type
   */
  @PostMapping(path = "/item-types", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.TYPE_CREATE)
  @CanFail({ProblemType.TYPE_KEY_TAKEN, ProblemType.NOT_FOUND, ProblemType.VALIDATION_FAILED})
  @ResponseStatus(HttpStatus.CREATED)
  public ResponseEntity<TypeAdministration.ItemTypeView> createItemType(
      @Valid @RequestBody CreateItemTypeRequest request,
      @AuthenticationPrincipal AuthenticatedUser user) {
    TypeAdministration.ItemTypeView view =
        types.createItemType(
            new TypeAdministration.CreateItemTypeCommand(
                request.key(),
                TypeKind.valueOf(request.kind().toUpperCase(java.util.Locale.ROOT)),
                request.parentId(),
                request.icon()),
            user.userId());
    return ResponseEntity.created(URI.create("/api/v1/catalog/item-types/" + view.id())).body(view);
  }

  /**
   * Archives an item type: offered for nothing new, still referenced by what exists.
   *
   * @param id the type
   * @param user the authenticated caller
   * @return the archived type
   */
  @PostMapping(path = "/item-types/{id}/archive", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.TYPE_DELETE)
  @CanFail({ProblemType.NOT_FOUND, ProblemType.VALIDATION_FAILED})
  public TypeAdministration.ItemTypeView archiveItemType(
      @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser user) {
    return types.archiveItemType(id, user.userId());
  }

  // -------------------------------------------------------------------------
  // Location categories
  // -------------------------------------------------------------------------

  /**
   * Every location category of the tenant, with its versions.
   *
   * <p>Beside {@code GET /api/v1/locations/categories}, which answers the picker a person uses when
   * creating a place and carries nothing about versions or drafts. This one is the administrative
   * view, and they are different questions rather than one answered twice.
   *
   * @param cursor an opaque cursor from a previous page, or omitted for the first
   * @param limit how many at most; capped at 200 by the service
   * @return the page and a cursor for the next one
   */
  @GetMapping(path = "/location-categories", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.TYPE_READ)
  @CanFail(ProblemType.MALFORMED_REQUEST)
  public TypeAdministration.CategoryPage categories(
      @RequestParam(required = false) @Size(max = 500) String cursor,
      @RequestParam(required = false, defaultValue = "50") @Positive @Max(200) int limit) {
    return types.categories(cursor, limit);
  }

  /**
   * Creates a location category, with an empty draft version.
   *
   * @param request the key, labels and whether its places travel with their contents
   * @param user the authenticated caller
   * @return the new category
   */
  @PostMapping(path = "/location-categories", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.TYPE_CREATE)
  @CanFail({ProblemType.TYPE_KEY_TAKEN, ProblemType.VALIDATION_FAILED})
  @ResponseStatus(HttpStatus.CREATED)
  public ResponseEntity<TypeAdministration.CategoryView> createCategory(
      @Valid @RequestBody CreateCategoryRequest request,
      @AuthenticationPrincipal AuthenticatedUser user) {
    TypeAdministration.CategoryView view =
        types.createCategory(
            new TypeAdministration.CreateCategoryCommand(
                request.key(), request.labels(), request.mobile()),
            user.userId());
    return ResponseEntity.created(
            URI.create("/api/v1/catalog/location-categories/" + view.id()))
        .body(view);
  }

  /**
   * Archives a location category.
   *
   * @param id the category
   * @param user the authenticated caller
   * @return the archived category
   */
  @PostMapping(
      path = "/location-categories/{id}/archive",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.TYPE_DELETE)
  @CanFail(ProblemType.NOT_FOUND)
  public TypeAdministration.CategoryView archiveCategory(
      @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser user) {
    return types.archiveCategory(id, user.userId());
  }

  /**
   * What a category takes underneath it (REQ-CORE-047).
   *
   * @param id the category
   * @return the rule; an empty list means it takes everything
   */
  @GetMapping(
      path = "/location-categories/{id}/child-categories",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.TYPE_READ)
  @CanFail(ProblemType.NOT_FOUND)
  public TypeAdministration.ChildCategoryRuleView childCategories(@PathVariable UUID id) {
    return types.childCategories(id);
  }

  /**
   * Replaces what a category takes underneath it (REQ-CORE-047).
   *
   * <p>A {@code PUT} of the whole set, because the rule <em>is</em> the set: there is no sensible
   * half of a whitelist, and a client that sends it whole is idempotent by construction. An empty
   * list withdraws the restriction.
   *
   * @param id the category
   * @param request the categories it will take
   * @param user the authenticated caller
   * @return the rule as it now stands
   */
  @PutMapping(
      path = "/location-categories/{id}/child-categories",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.TYPE_UPDATE)
  @CanFail({ProblemType.NOT_FOUND, ProblemType.VALIDATION_FAILED})
  public TypeAdministration.ChildCategoryRuleView setChildCategories(
      @PathVariable UUID id,
      @Valid @RequestBody ChildCategoriesRequest request,
      @AuthenticationPrincipal AuthenticatedUser user) {
    return types.setChildCategories(
        id, request.permitted() == null ? List.of() : request.permitted(), user.userId());
  }

  // -------------------------------------------------------------------------
  // Versions
  // -------------------------------------------------------------------------

  /**
   * Starts a draft of a type or a category, copying what is published now.
   *
   * @param ownerId the item type or location category
   * @param user the authenticated caller
   * @return the draft with its fields
   */
  @PostMapping(path = "/types/{ownerId}/versions", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.TYPE_UPDATE)
  @CanFail({ProblemType.NOT_FOUND, ProblemType.VALIDATION_FAILED})
  @ResponseStatus(HttpStatus.CREATED)
  public ResponseEntity<TypeAdministration.VersionView> draftVersion(
      @PathVariable UUID ownerId, @AuthenticationPrincipal AuthenticatedUser user) {
    TypeAdministration.VersionView view = types.draftVersion(ownerId, user.userId());
    return ResponseEntity.created(URI.create("/api/v1/catalog/versions/" + view.id())).body(view);
  }

  /**
   * One version with every field it declares.
   *
   * @param id the version
   * @return the version and its fields, in display order
   */
  @GetMapping(path = "/versions/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.TYPE_READ)
  @CanFail(ProblemType.NOT_FOUND)
  public TypeAdministration.VersionView version(@PathVariable UUID id) {
    return types.version(id);
  }

  /**
   * The generated JSON Schema of a version (REQ-CORE-027).
   *
   * <p>Served as {@code application/schema+json} and as the stored text rather than as a re-rendered
   * tree: this is the document the server validates with (ADR-0056), and a client that receives
   * different bytes would be checking something else.
   *
   * @param id the version
   * @return the schema document
   */
  @GetMapping(path = "/versions/{id}/schema", produces = "application/schema+json")
  @RequiresPermission(Permission.TYPE_READ)
  @CanFail(ProblemType.NOT_FOUND)
  public ResponseEntity<String> schema(@PathVariable UUID id) {
    return ResponseEntity.ok()
        .contentType(MediaType.valueOf("application/schema+json"))
        .body(registry.jsonSchema(id));
  }

  /**
   * Publishes a draft.
   *
   * @param id the draft
   * @param user the authenticated caller
   * @return the published version with its materialised field set
   */
  @PostMapping(path = "/versions/{id}/publish", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.TYPE_UPDATE)
  @CanFail({
    ProblemType.NOT_FOUND,
    ProblemType.VERSION_FROZEN,
    ProblemType.CONSTRAINT_LOOSENED
  })
  public TypeAdministration.VersionView publish(
      @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser user) {
    return types.publish(id, user.userId());
  }

  // -------------------------------------------------------------------------
  // Fields
  // -------------------------------------------------------------------------

  /**
   * Adds a field to a draft.
   *
   * @param versionId the draft
   * @param request the field
   * @param user the authenticated caller
   * @return the new field
   */
  @PostMapping(path = "/versions/{versionId}/fields", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.TYPE_UPDATE)
  @CanFail({
    ProblemType.NOT_FOUND,
    ProblemType.VERSION_FROZEN,
    ProblemType.TYPE_KEY_TAKEN,
    ProblemType.VALIDATION_FAILED
  })
  @ResponseStatus(HttpStatus.CREATED)
  public ResponseEntity<FieldDefinitionView> addField(
      @PathVariable UUID versionId,
      @Valid @RequestBody FieldRequest request,
      @AuthenticationPrincipal AuthenticatedUser user) {
    FieldDefinitionView view = types.addField(versionId, request.toCommand(), user.userId());
    return ResponseEntity.created(URI.create("/api/v1/catalog/fields/" + view.id())).body(view);
  }

  /**
   * Changes a field of a draft. The key is not changed, because items already carry it.
   *
   * @param id the field
   * @param request the new state
   * @param user the authenticated caller
   * @return the changed field
   */
  @PutMapping(path = "/fields/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.TYPE_UPDATE)
  @CanFail({ProblemType.NOT_FOUND, ProblemType.VERSION_FROZEN, ProblemType.VALIDATION_FAILED})
  public FieldDefinitionView updateField(
      @PathVariable UUID id,
      @Valid @RequestBody FieldRequest request,
      @AuthenticationPrincipal AuthenticatedUser user) {
    return types.updateField(id, request.toCommand(), user.userId());
  }

  /**
   * Hides a field from input and keeps every value under it (REQ-CORE-026).
   *
   * @param id the field
   * @param user the authenticated caller
   */
  @PostMapping(path = "/fields/{id}/deprecate")
  @RequiresPermission(Permission.TYPE_UPDATE)
  @CanFail({ProblemType.NOT_FOUND, ProblemType.VERSION_FROZEN})
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deprecateField(
      @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser user) {
    types.deprecateField(id, user.userId());
  }

  /**
   * What removing a field would destroy — the affected set REQ-CORE-026 shows first.
   *
   * @param id the field
   * @return the field's key and how many rows carry a value for it
   */
  @GetMapping(path = "/fields/{id}/removal-preview", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.TYPE_DELETE)
  @CanFail(ProblemType.NOT_FOUND)
  public TypeAdministration.FieldRemovalPreview previewFieldRemoval(@PathVariable UUID id) {
    return types.previewFieldRemoval(id);
  }

  /**
   * Removes a deprecated field and every value stored under it.
   *
   * @param id the field
   * @param user the authenticated caller
   */
  @DeleteMapping(path = "/fields/{id}")
  @RequiresPermission(Permission.TYPE_DELETE)
  @CanFail({ProblemType.NOT_FOUND, ProblemType.VALIDATION_FAILED})
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void removeField(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser user) {
    types.removeField(id, user.userId());
  }

  // -------------------------------------------------------------------------
  // Value lists
  // -------------------------------------------------------------------------

  /**
   * One page of the tenant's value lists, each with its entries.
   *
   * @param cursor an opaque cursor from a previous page, or omitted for the first
   * @param limit how many at most; capped at 200 by the service
   * @return the page and a cursor for the next one
   */
  @GetMapping(path = "/value-lists", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.VALUE_LIST_READ)
  @CanFail(ProblemType.MALFORMED_REQUEST)
  public TypeAdministration.ValueListPage valueLists(
      @RequestParam(required = false) @Size(max = 500) String cursor,
      @RequestParam(required = false, defaultValue = "50") @Positive @Max(200) int limit) {
    return types.valueLists(cursor, limit);
  }

  /**
   * Creates an empty value list.
   *
   * @param request the key and labels
   * @param user the authenticated caller
   * @return the new list
   */
  @PostMapping(path = "/value-lists", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.VALUE_LIST_CREATE)
  @CanFail({ProblemType.TYPE_KEY_TAKEN, ProblemType.VALIDATION_FAILED})
  @ResponseStatus(HttpStatus.CREATED)
  public ResponseEntity<TypeAdministration.ValueListView> createValueList(
      @Valid @RequestBody CreateValueListRequest request,
      @AuthenticationPrincipal AuthenticatedUser user) {
    TypeAdministration.ValueListView view =
        types.createValueList(
            new TypeAdministration.CreateValueListCommand(request.key(), request.labels()),
            user.userId());
    return ResponseEntity.created(URI.create("/api/v1/catalog/value-lists/" + view.id()))
        .body(view);
  }

  /**
   * Adds a value to a list.
   *
   * @param id the list
   * @param request the value and its labels
   * @param user the authenticated caller
   * @return the entry
   */
  @PostMapping(path = "/value-lists/{id}/entries", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.VALUE_LIST_UPDATE)
  @CanFail({ProblemType.NOT_FOUND, ProblemType.TYPE_KEY_TAKEN, ProblemType.VALIDATION_FAILED})
  @ResponseStatus(HttpStatus.CREATED)
  public ResponseEntity<TypeAdministration.ValueListEntryView> addEntry(
      @PathVariable UUID id,
      @Valid @RequestBody AddEntryRequest request,
      @AuthenticationPrincipal AuthenticatedUser user) {
    TypeAdministration.ValueListEntryView view =
        types.addValueListEntry(
            id,
            new TypeAdministration.AddEntryCommand(
                request.value(), request.labels(), request.displayOrder()),
            user.userId());
    return ResponseEntity.created(
            URI.create("/api/v1/catalog/value-list-entries/" + view.id()))
        .body(view);
  }

  /**
   * Stops offering a value for new input, leaving it valid wherever it is stored.
   *
   * @param id the entry
   * @param user the authenticated caller
   */
  @PostMapping(path = "/value-list-entries/{id}/archive")
  @RequiresPermission(Permission.VALUE_LIST_UPDATE)
  @CanFail(ProblemType.NOT_FOUND)
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void archiveEntry(
      @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser user) {
    types.archiveValueListEntry(id, user.userId());
  }

  // -------------------------------------------------------------------------
  // Request bodies
  // -------------------------------------------------------------------------

  /**
   * What to call a new item type.
   *
   * @param key the stable key, lower-case and unique per tenant
   * @param kind {@code PHYSICAL} or {@code DIGITAL}
   * @param parentId the type it inherits from, or omitted
   * @param icon an icon name for clients, or omitted
   */
  public record CreateItemTypeRequest(
      @NotBlank @Size(max = 64) String key,
      @NotBlank @Size(max = 16) String kind,
      UUID parentId,
      @Size(max = 64) String icon) {}

  /**
   * The categories a category is to take underneath it (REQ-CORE-047).
   *
   * @param permitted the categories, by id. Omitted or empty withdraws the restriction, so the
   *     category takes everything again; the cap is the one every collection here carries
   */
  public record ChildCategoriesRequest(@Size(max = 200) List<UUID> permitted) {}

  /**
   * What to call a new location category.
   *
   * @param key the stable key
   * @param labels the name per language tag
   * @param mobile whether its places travel with their contents
   */
  public record CreateCategoryRequest(
      @NotBlank @Size(max = 64) String key, Map<String, String> labels, boolean mobile) {}

  /**
   * A field, as it is added or changed.
   *
   * @param key the attribute key; ignored when changing
   * @param dataType the token of one of the sixteen kinds, for example {@code multi-enum}
   * @param labels the field's name per language tag; at least one
   * @param helpTexts the explanatory line per language tag
   * @param required whether a value must be present
   * @param defaultValue the JSON text of the starting value
   * @param constraints the declarative limits
   * @param valueListId the list an enumeration draws from
   * @param visibility the one condition deciding whether a client shows it
   * @param group the form section
   * @param displayOrder the position within the group
   * @param searchable whether it is mirrored for filtering
   * @param sortable whether it is mirrored for ordering
   * @param facetable whether it is mirrored for counting
   * @param sensitive whether reading it needs a permission of its own
   */
  public record FieldRequest(
      @Size(max = 64) String key,
      @NotBlank @Size(max = 16) String dataType,
      Map<String, String> labels,
      Map<String, String> helpTexts,
      boolean required,
      @Size(max = 4096) String defaultValue,
      ConstraintsRequest constraints,
      UUID valueListId,
      VisibilityRequest visibility,
      @Size(max = 64) String group,
      int displayOrder,
      boolean searchable,
      boolean sortable,
      boolean facetable,
      boolean sensitive) {

    /**
     * The command behind this request.
     *
     * @return the field as the service takes it
     */
    TypeAdministration.FieldCommand toCommand() {
      return new TypeAdministration.FieldCommand(
          key,
          FieldDataType.ofToken(dataType),
          labels == null ? Map.of() : labels,
          helpTexts == null ? Map.of() : helpTexts,
          required,
          defaultValue,
          constraints == null ? FieldConstraints.NONE : constraints.toConstraints(),
          valueListId,
          visibility == null ? null : visibility.toRule(),
          group,
          displayOrder,
          searchable,
          sortable,
          facetable,
          sensitive);
    }
  }

  /**
   * The declarative limits on a field.
   *
   * @param pattern a regular expression the whole value must match
   * @param min the smallest permitted number
   * @param max the largest permitted number
   * @param minLength the fewest characters
   * @param maxLength the most characters
   * @param unit the one unit or ISO 4217 code accepted
   * @param referenceKind what a {@code reference} may point at: {@code ITEM}, {@code LOCATION} or
   *     {@code VALUE_LIST}
   */
  public record ConstraintsRequest(
      @Size(max = 256) String pattern,
      BigDecimal min,
      BigDecimal max,
      Integer minLength,
      Integer maxLength,
      @Size(max = 16) String unit,
      @Size(max = 16) String referenceKind) {

    /**
     * The constraints behind this request.
     *
     * @return the limits as the service takes them
     */
    FieldConstraints toConstraints() {
      return new FieldConstraints(
          pattern,
          min,
          max,
          minLength,
          maxLength,
          unit,
          referenceKind == null
              ? null
              : FieldConstraints.ReferenceKind.valueOf(
                  referenceKind.toUpperCase(java.util.Locale.ROOT)));
    }
  }

  /**
   * The one condition that decides whether a client shows a field.
   *
   * @param field the key of the field watched
   * @param operator {@code equals}, {@code notEquals}, {@code present} or {@code absent}
   * @param value the JSON text compared against, for the two operators that compare
   */
  public record VisibilityRequest(
      @NotBlank @Size(max = 64) String field,
      @NotBlank @Size(max = 16) String operator,
      @Size(max = 256) String value) {

    /**
     * The rule behind this request.
     *
     * @return the rule as the service takes it
     */
    VisibilityRule toRule() {
      return new VisibilityRule(field, VisibilityRule.Operator.ofToken(operator), value);
    }
  }

  /**
   * What to call a new value list.
   *
   * @param key the stable key
   * @param labels the list's name per language tag
   */
  public record CreateValueListRequest(
      @NotBlank @Size(max = 64) String key, Map<String, String> labels) {}

  /**
   * A value to add to a list.
   *
   * @param value what lands in an item's attributes
   * @param labels its readable form per language tag
   * @param displayOrder its position
   */
  public record AddEntryRequest(
      @NotBlank @Size(max = 128) String value, Map<String, String> labels, int displayOrder) {}
}
