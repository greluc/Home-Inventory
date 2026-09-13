/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.authorization.api;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * The built-in roles and what each one may do (04 §4.3).
 *
 * <p>Six of them, and the same six the {@code tenancy.membership} check constraint allows. Stage 0
 * only ever writes {@code OWNER} — the person who created the instance — but the whole ladder is
 * defined here rather than grown later, because a permission set that arrives one role at a time is
 * a permission set nobody ever reviews as a whole.
 *
 * <p>Tenant-owned roles arrive in stage 1 and <b>extend</b> these; they do not replace them
 * (04 §4.3). The grants below are therefore a floor, not the final word.
 *
 * <h2>Reading the ladder</h2>
 *
 * <ul>
 *   <li>{@code GUEST} may read items and locations and nothing else. It exists for a share link.
 *   <li>{@code VIEWER} adds search: reading a list is a different capability from finding one thing
 *       in it, because a search surface is also an enumeration surface.
 *   <li>{@code CONTRIBUTOR} may add — items, locations, photos — and may change what exists, but
 *       may delete nothing. It is the role for somebody helping with a stocktake.
 *   <li>{@code MEMBER} adds deletion. Deletion is two-stage from stage 1 onwards
 *       ({@code REQ-CORE-009}), which is what makes granting it reasonable.
 *   <li>{@code ADMIN} and {@code OWNER} hold everything this stage defines. They diverge in stage 1,
 *       where tenant deletion and role granting appear and belong to {@code OWNER} alone.
 * </ul>
 */
public enum Role {

  /** Read-only, for a share link. */
  GUEST(EnumSet.of(
      Permission.ITEM_READ,
      Permission.LOCATION_READ,
      Permission.MEDIA_READ,
      // Even a share link needs these: without the definitions behind an item's
      // attributes a client shows unlabelled keys, so withholding them hides
      // nothing and breaks the page.
      Permission.TYPE_READ,
      Permission.VALUE_LIST_READ)),

  /** Read and search. */
  VIEWER(EnumSet.of(
      Permission.ITEM_READ,
      Permission.LOCATION_READ,
      Permission.MEDIA_READ,
      Permission.SEARCH_QUERY,
      Permission.TYPE_READ,
      Permission.VALUE_LIST_READ)),

  /** Adds and changes, deletes nothing. */
  CONTRIBUTOR(EnumSet.of(
      Permission.ITEM_READ,
      Permission.ITEM_CREATE,
      Permission.ITEM_UPDATE,
      Permission.LOCATION_READ,
      Permission.LOCATION_CREATE,
      Permission.LOCATION_UPDATE,
      Permission.MEDIA_READ,
      Permission.MEDIA_CREATE,
      Permission.SEARCH_QUERY,
      Permission.TYPE_READ,
      Permission.VALUE_LIST_READ)),

  /** Everything a person working with the inventory needs, deletion included. */
  MEMBER(EnumSet.allOf(Permission.class)),

  /** Everything stage 0 defines; diverges from {@code OWNER} in stage 1. */
  ADMIN(EnumSet.allOf(Permission.class)),

  /** The person the tenant belongs to. */
  OWNER(EnumSet.allOf(Permission.class));

  private final Set<Permission> permissions;

  Role(Set<Permission> permissions) {
    this.permissions = Collections.unmodifiableSet(permissions);
  }

  /**
   * What this role may do.
   *
   * @return an unmodifiable set
   */
  public Set<Permission> permissions() {
    return permissions;
  }

  /**
   * Whether this role holds a permission.
   *
   * @param permission the permission in question
   * @return true when it is granted
   */
  public boolean holds(Permission permission) {
    return permissions.contains(permission);
  }

  /**
   * Parses a role name as the database stores it.
   *
   * <p>Returns empty rather than throwing, and the caller denies. A row with a role this build does
   * not know — a downgrade, or a hand-edited membership — must not be treated as an error that
   * somebody works around; it must simply grant nothing.
   *
   * @param name the stored name
   * @return the role, or empty when it is not one of the six
   */
  public static Optional<Role> named(String name) {
    if (name == null) {
      return Optional.empty();
    }
    for (Role role : values()) {
      if (role.name().equals(name)) {
        return Optional.of(role);
      }
    }
    return Optional.empty();
  }
}
