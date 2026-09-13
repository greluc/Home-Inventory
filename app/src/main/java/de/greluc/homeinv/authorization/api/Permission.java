/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.authorization.api;

/**
 * Every permission this stage defines, as {@code <block>:<resource>:<action>} (04 §4.3).
 *
 * <p>An enum rather than free strings, and that is the whole reason this type exists: a permission
 * named by a string is a permission that can be misspelt at the point of the check, and a misspelt
 * permission is one nobody holds — so the endpoint denies everyone, which looks like a
 * configuration problem and gets "fixed" by removing the check.
 *
 * <p>Stage 1 adds tenant-owned roles that <em>extend</em> the built-ins (04 §4.3). They will grant
 * combinations of these same permissions; the catalogue of permissions is core code and stays here,
 * because a tenant defining its own permission would be a tenant defining what the application
 * does.
 */
public enum Permission {

  /** Read an item. */
  ITEM_READ("inventory:item:read"),
  /** Create an item. */
  ITEM_CREATE("inventory:item:create"),
  /** Change an item. */
  ITEM_UPDATE("inventory:item:update"),
  /** Delete an item. */
  ITEM_DELETE("inventory:item:delete"),

  /** Read a location. */
  LOCATION_READ("locations:location:read"),
  /** Create a location. */
  LOCATION_CREATE("locations:location:create"),
  /** Change a location. */
  LOCATION_UPDATE("locations:location:update"),
  /** Delete a location. */
  LOCATION_DELETE("locations:location:delete"),

  /** Search across the tenant's items. */
  SEARCH_QUERY("search:index:query"),

  /** Read a media object's metadata and obtain a signed URL for it. */
  MEDIA_READ("media:object:read"),
  /** Upload a media object and attach it. */
  MEDIA_CREATE("media:object:create"),
  /** Detach or delete a media object. */
  MEDIA_DELETE("media:object:delete"),

  /**
   * Read the tenant's type system: types, categories, their fields and their schemas.
   *
   * <p>Held by every role, the share link included. A client cannot render an item's attributes
   * without the definitions behind them, so withholding this would not hide the values — it would
   * show them as unlabelled keys.
   */
  TYPE_READ("catalog:type:read"),
  /** Create an item type or a location category. */
  TYPE_CREATE("catalog:type:create"),
  /** Change a type, add or tighten a field, publish a version. */
  TYPE_UPDATE("catalog:type:update"),
  /** Archive a type, or finally remove a deprecated field and the values under it. */
  TYPE_DELETE("catalog:type:delete"),

  /** Read the tenant's value lists, which an enumeration field draws on. */
  VALUE_LIST_READ("catalog:value-list:read"),
  /** Create a value list. */
  VALUE_LIST_CREATE("catalog:value-list:create"),
  /** Add an entry to a value list, relabel one, or archive one. */
  VALUE_LIST_UPDATE("catalog:value-list:update"),

  /** Read the tenant's tags and the groups they sit in. */
  TAG_READ("tagging:tag:read"),
  /** Create a tag or a tag group. */
  TAG_CREATE("tagging:tag:create"),
  /** Rename a tag, recolour it, move it between groups, or merge two. */
  TAG_UPDATE("tagging:tag:update"),
  /**
   * Put a tag on an item or a place, or take one off.
   *
   * <p>Separate from {@link #TAG_UPDATE}: labelling things is what everybody working with the
   * inventory does, and editing the tag vocabulary is not. A contributor helping with a stocktake
   * holds this and not that.
   */
  TAG_ASSIGN("tagging:tag:assign");

  private final String id;

  Permission(String id) {
    this.id = id;
  }

  /**
   * The stable identifier, as it appears in a role definition and in an audit entry.
   *
   * <p>It is the identifier and not {@link #name()} because the enum constant is a Java name and
   * this is data: a tenant-owned role in stage 1 stores it, an audit entry from last year contains
   * it, and neither should change because a constant was renamed.
   *
   * @return the {@code <block>:<resource>:<action>} identifier
   */
  public String id() {
    return id;
  }
}
