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
 * <p>Three bands, and the line between them is what somebody is being trusted with rather than how
 * senior they are: <b>content</b> is the things in the inventory, <b>configuration</b> is the shape
 * those things have and who may touch them, and <b>ownership</b> is the tenant itself.
 *
 * <ul>
 *   <li>{@code GUEST} may read items and locations and nothing else. It exists for a share link.
 *   <li>{@code VIEWER} adds search: reading a list is a different capability from finding one thing
 *       in it, because a search surface is also an enumeration surface.
 *   <li>{@code CONTRIBUTOR} may add — items, locations, photos — and may change what exists, but
 *       may delete nothing. It is the role for somebody helping with a stocktake.
 *   <li>{@code MEMBER} holds the whole of the content band: deletion included, final removal
 *       included, and the tag vocabulary, because a tag is something people write rather than
 *       something the tenant is configured with. Deletion is two-stage ({@code REQ-CORE-009}),
 *       which is what makes granting it reasonable.
 *   <li>{@code ADMIN} adds configuration: the type system, the value lists, and the members —
 *       inviting, promoting, removing. Not content it does not already hold, but the power to
 *       decide what content may look like and who may make it.
 *   <li>{@code OWNER} is the person the tenant belongs to. It holds everything {@code ADMIN} does
 *       and one thing more: asking for the tenant to be erased ({@code REQ-TEN-011}). That is the
 *       act nobody can undo once the grace period is over, and an administrator who could start it
 *       could start it on their last day. The two are also separated by {@code REQ-TEN-010}: only
 *       an {@code OWNER} may make somebody an {@code OWNER}, because ownership is a relationship
 *       and not a permission set.
 * </ul>
 *
 * <p>The change from stage 0 is {@code MEMBER}. It held every permission there was, because stage 0
 * had no type system and no member administration to withhold; it now holds the content band, and
 * configuring the tenant is {@code ADMIN}'s.
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
      Permission.VALUE_LIST_READ,
      Permission.TAG_READ)),

  /** Read and search. */
  VIEWER(EnumSet.of(
      Permission.ITEM_READ,
      Permission.LOCATION_READ,
      Permission.MEDIA_READ,
      Permission.SEARCH_QUERY,
      Permission.TYPE_READ,
      Permission.VALUE_LIST_READ,
      Permission.TAG_READ,
      // Which tenant this is. A share link is pointed at one thing and is told
      // nothing about the tenant around it; anybody who signed in to reach this
      // one is looking at it in a switcher already.
      Permission.TENANT_READ)),

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
      Permission.VALUE_LIST_READ,
      Permission.TAG_READ,
      // Labelling things is what a contributor does; editing the tag vocabulary
      // is a different capability and is not granted here.
      Permission.TAG_ASSIGN,
      Permission.TENANT_READ)),

  /** The whole content band: everything in the inventory, deletion included. */
  MEMBER(EnumSet.of(
      Permission.ITEM_READ,
      Permission.ITEM_CREATE,
      Permission.ITEM_UPDATE,
      Permission.ITEM_DELETE,
      Permission.ITEM_PURGE,
      Permission.LOCATION_READ,
      Permission.LOCATION_CREATE,
      Permission.LOCATION_UPDATE,
      Permission.LOCATION_DELETE,
      Permission.MEDIA_READ,
      Permission.MEDIA_CREATE,
      Permission.MEDIA_DELETE,
      Permission.SEARCH_QUERY,
      Permission.TYPE_READ,
      Permission.VALUE_LIST_READ,
      // The tag vocabulary is content: people write tags, they do not configure
      // the tenant with them. Merging two is the same capability as renaming one.
      Permission.TAG_READ,
      Permission.TAG_CREATE,
      Permission.TAG_UPDATE,
      Permission.TAG_ASSIGN,
      // A saved search is the tenant's, so writing one is a change to what
      // everybody sees; that is a member's to make. Taking one away from
      // everybody is not, and is ADMIN's (REQ-SRCH-008).
      Permission.SAVED_SEARCH_WRITE,
      // What foreign code may reach here is something anybody working in the
      // tenant has an interest in knowing; agreeing to it is ADMIN's.
      Permission.PLUGIN_READ,
      Permission.TENANT_READ,
      // Who else is here. A person working in a shared inventory can see who
      // they are sharing it with; changing that list is ADMIN's.
      Permission.MEMBER_READ)),

  /** The content band plus configuration: the type system, and the members. */
  ADMIN(EnumSet.of(
      Permission.ITEM_READ,
      Permission.ITEM_CREATE,
      Permission.ITEM_UPDATE,
      Permission.ITEM_DELETE,
      Permission.ITEM_PURGE,
      Permission.LOCATION_READ,
      Permission.LOCATION_CREATE,
      Permission.LOCATION_UPDATE,
      Permission.LOCATION_DELETE,
      Permission.MEDIA_READ,
      Permission.MEDIA_CREATE,
      Permission.MEDIA_DELETE,
      Permission.SEARCH_QUERY,
      Permission.TYPE_READ,
      Permission.TYPE_CREATE,
      Permission.TYPE_UPDATE,
      Permission.TYPE_DELETE,
      Permission.VALUE_LIST_READ,
      Permission.VALUE_LIST_CREATE,
      Permission.VALUE_LIST_UPDATE,
      Permission.TAG_READ,
      Permission.TAG_CREATE,
      Permission.TAG_UPDATE,
      Permission.TAG_ASSIGN,
      Permission.SAVED_SEARCH_WRITE,
      Permission.SAVED_SEARCH_DELETE,
      Permission.PLUGIN_READ,
      Permission.PLUGIN_CONSENT,
      Permission.TENANT_READ,
      Permission.TENANT_UPDATE,
      Permission.MEMBER_READ,
      Permission.MEMBER_INVITE,
      Permission.MEMBER_UPDATE,
      Permission.MEMBER_REMOVE,
      Permission.SERVICE_ACCOUNT_ADMINISTER)),
  // Deliberately NOT Permission.TENANT_DELETE: that is OWNER's, and it is what
  // makes these two different permission sets rather than only different in what
  // they may grant (REQ-TEN-011).

  /**
   * The person the tenant belongs to.
   *
   * <p>Everything {@code ADMIN} holds and {@code tenancy:tenant:delete} besides, which is the
   * permission the two sets differ by. They differ in authority too: REQ-TEN-010 reserves granting
   * {@code OWNER} to an {@code OWNER}, because ownership is a relationship rather than a set.
   */
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
