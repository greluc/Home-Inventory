/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.catalog.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reading the tenant's type system: what a version consists of, and which version is current.
 *
 * <h2>Why the blocks that store attributes cannot look for themselves</h2>
 *
 * <p>{@code inventory} and {@code locations} both hold a JSONB column whose meaning lives in this
 * schema. Reading {@code catalog.field_definition} from either of them would be a block reaching
 * into a foreign schema, which 04 §4.5 forbids and {@code ModularityTest} fails the build over — so
 * everything either of them needs to know about a field arrives through this port.
 *
 * <p>Every method is tenant-scoped by row-level security rather than by an argument: the tenant of
 * the caller is the tenant of the answer, and a version id from another tenant is not found rather
 * than being found and refused (REQ-SEC-005).
 */
public interface TypeRegistry {

  /**
   * The published version an item type currently points at.
   *
   * <p>A write resolves the type a client named to the version the item will reference, and it
   * resolves it once, at the moment of the write: an item keeps the version it was written against
   * even after the type moves on, which is what makes a type change devalue nothing (REQ-CORE-025).
   *
   * @param itemTypeId the type
   * @return the id of its newest published version
   * @throws UnknownTypeException when the type does not exist for this tenant, is archived, or has
   *     no published version at all — a draft is referenced by nothing
   */
  UUID publishedItemTypeVersion(UUID itemTypeId);

  /**
   * The published version a location category currently points at.
   *
   * @param categoryId the category
   * @return the id of its newest published version
   * @throws UnknownTypeException when the category does not exist for this tenant, is archived, or
   *     has no published version
   */
  UUID publishedCategoryVersion(UUID categoryId);

  /**
   * Which category a category version belongs to.
   *
   * <p>A location stores the version it was written against (REQ-CORE-025); a rule about what goes
   * where is about the category. Only this block can turn one into the other — the tables that
   * answer it are its own (04 §4.5).
   *
   * @param categoryVersionId the version a location stores
   * @return the category it is a version of
   * @throws de.greluc.homeinv.platform.NotFoundException when this tenant has no such version
   */
  UUID categoryOfVersion(UUID categoryVersionId);

  /**
   * Whether a category takes another one underneath it (REQ-CORE-047).
   *
   * <p>The restriction is optional and is read as a whitelist that only exists once it has an
   * entry: a category with no rule takes everything, and one with a rule takes exactly what it
   * names. That is what makes the feature addable to a running tenant without breaking the tree it
   * already has.
   *
   * @param parentCategoryId the category of the place underneath which something would go
   * @param childCategoryId the category of the place that would go there
   * @return true when the tree may have that shape
   */
  boolean permitsChildCategory(UUID parentCategoryId, UUID childCategoryId);

  /**
   * The categories that a set of category versions belong to.
   *
   * <p>Batched because it serves a listing: a page of locations carries up to two hundred version
   * ids, and a client wants the category, not the version it happened to be written against.
   *
   * @param categoryVersionIds the versions, duplicates tolerated
   * @return version id to category id, missing entries for ids this tenant cannot see
   */
  Map<UUID, UUID> categoriesOfVersions(Collection<UUID> categoryVersionIds);

  /**
   * The item type that an item type version belongs to.
   *
   * @param itemTypeVersionIds the versions, duplicates tolerated
   * @return version id to type id, missing entries for ids this tenant cannot see
   */
  Map<UUID, UUID> itemTypesOfVersions(Collection<UUID> itemTypeVersionIds);

  /**
   * Every field a version declares, deprecated ones included, in display order.
   *
   * <p>Deprecated fields are included because a reader has to render values that already exist
   * (REQ-CORE-026); {@link FieldDefinitionView#deprecated()} is what an input form filters on.
   *
   * <p>One method for both kinds of version. Their ids come from two tables and cannot collide, and
   * a caller that has a version id does not care which table it came from — {@code locations} asks
   * this with a category version and {@code inventory} with a type version, and both get the fields
   * of the thing they named.
   *
   * @param typeVersionId an item type version or a location category version
   * @return the fields, ordered by group, then position, then key; empty when the version declares
   *     none, which is what a freshly created type looks like
   */
  List<FieldDefinitionView> fields(UUID typeVersionId);

  /**
   * Every attribute key this tenant may query by, with the type it holds (REQ-SRCH-003, 004, 002).
   *
   * <p>The allowlist CLAUDE.md requires: a field or sort name in a statement comes from
   * {@code field_definition} and never from what a caller typed. A key that is not here cannot be
   * filtered, sorted or counted by, and the request is refused rather than quietly ignored.
   *
   * <p>Across the tenant rather than per type version, because a query spans types: "everything
   * over 100 euro" is asked of the whole inventory, not of one kind of thing.
   *
   * <p><b>A key whose types disagree is not queryable.</b> The same key may be declared by several
   * types — {@code model} as text on one and as an integer on another — and {@code item_attr_index}
   * keeps one column per storage class, so such a key would live in two columns at once. Ordering
   * across those two is meaningless and filtering across them is worse, because it would look like
   * it worked. Such a key is left out, which is rare and honest.
   *
   * @return the queryable keys, each with its data type and what may be done with it
   */
  List<QueryableField> queryableFields();

  /**
   * The item-type <b>version</b> ids belonging to the types a tenant knows by these keys.
   *
   * <p>What {@code filter=type:power-tool} resolves to. A key is what a person writes, and what an
   * item holds is {@code item_type_version_id} — an item is written against the version of the
   * type that was published when it was written (ADR-0004). Narrowing by type therefore means
   * naming every version of it, and the translation belongs here because both the key and the
   * version chain are {@code catalog}'s to own: {@code search} composes the query and never reads
   * this schema (ADR-0002).
   *
   * <p><b>Every</b> version, drafts and superseded ones included. An item written last year against
   * version 2 is still a power tool today, and a list that showed only the current version's items
   * would be missing most of them.
   *
   * <p>A key nothing is called yields nothing rather than a refusal: it matches no item, which is a
   * true answer to "show me the power tools" in a tenant that has none. An archived type still
   * answers, because the items written against it still exist.
   *
   * @param keys the type keys, possibly none
   * @return the version ids, in no particular order; empty when no key matches
   */
  List<UUID> itemTypeVersionsByKeys(Collection<String> keys);

  /**
   * What type each of these versions belongs to, and what that type sits under.
   *
   * <p>The other direction of {@link #itemTypeVersionsByKeys}, and what turns a count per
   * {@code item_type_version_id} into the {@code type} and {@code category} facets (REQ-SRCH-002).
   * Both come from one query because they come from one row: a category here is the type's parent
   * in the type tree, which is what {@code catalog.item_type.parent_id} holds (decided with the
   * owner 2026-09-14 — there is no separate item category, and the only table called "category"
   * describes a location).
   *
   * @param versionIds the version ids, possibly none
   * @return the identity of each version's type, keyed by version id; a version this tenant cannot
   *     see is simply absent
   */
  Map<UUID, TypeIdentity> typesOfVersions(Collection<UUID> versionIds);

  /**
   * What a type is called, and what it sits under.
   *
   * @param key the type's key, which is what a {@code type} filter names
   * @param parentKey the key of the type above it, or {@code null} for a type at the root — which
   *     therefore contributes no bucket to a {@code category} facet, because it is in none
   */
  record TypeIdentity(String key, String parentKey) {}

  /**
   * One attribute key a query may name.
   *
   * @param key the attribute key, as {@code item_attr_index.field_key} holds it
   * @param dataType what it holds, which decides the column a predicate reads
   * @param filterable whether any published version marks it {@code searchable}
   * @param sortable whether any published version marks it {@code sortable}
   * @param facetable whether any published version marks it {@code facetable}
   */
  record QueryableField(
      String key,
      de.greluc.homeinv.catalog.api.FieldDataType dataType,
      boolean filterable,
      boolean sortable,
      boolean facetable) {}

  /**
   * The generated JSON Schema of a version, as the document clients receive.
   *
   * <p>The same bytes the validator uses (ADR-0056). Returned as text rather than as a parsed tree
   * because that is what it is: a document that is served, cached and compared, and parsing it here
   * only to serialise it again would invite the two to differ.
   *
   * @param typeVersionId an item type version or a location category version
   * @return the schema document, a JSON object
   * @throws UnknownTypeException when no such version is visible to this tenant
   */
  String jsonSchema(UUID typeVersionId);

  /**
   * The values an {@code enum} or {@code multi-enum} field accepts.
   *
   * @param valueListId the list
   * @return the non-archived entries in display order; empty when the list has none, which makes
   *     every value invalid and is a configuration mistake the tenant can see
   */
  List<String> valueListEntries(UUID valueListId);

  /** Thrown when a type, category, version or value list is not visible to the current tenant. */
  class UnknownTypeException extends RuntimeException {

    /**
     * Names what was looked for, never what was found.
     *
     * @param message what could not be resolved
     */
    public UnknownTypeException(String message) {
      super(message);
    }
  }
}
