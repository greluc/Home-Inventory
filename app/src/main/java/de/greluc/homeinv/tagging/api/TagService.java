/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tagging.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tags: tenant-wide labels that hang on items and on places (REQ-CORE-060…063).
 *
 * <h2>Why tags exist beside the type system</h2>
 *
 * <p>A field is a question every thing of a type answers; a tag is a note a person sticks on one
 * thing. "Fragile" is not a property of being a box, and adding it as a field would ask every box
 * whether it is fragile. Tags cross types, cross items and places, and are created in the moment
 * they are needed — which is precisely what a type system must not be (ADR-0020).
 */
public interface TagService {

  /**
   * Creates a tag.
   *
   * @param command the name, optional group, colour and icon
   * @param actor the authenticated user
   * @return the new tag
   * @throws TagNameTakenException when a live tag of this tenant already carries that name,
   *     case-insensitively
   */
  TagView create(CreateTagCommand command, UUID actor);

  /**
   * One page of the tenant's tags, merged ones excluded.
   *
   * @param cursor an opaque cursor from a previous page, or {@code null} for the first
   * @param limit how many at most; capped at 200
   * @return the page and a cursor for the next one
   */
  TagPage tags(String cursor, int limit);

  /**
   * Changes a tag's name, group, colour or icon.
   *
   * @param tagId the tag
   * @param command the new state
   * @param actor the authenticated user
   * @return the changed tag
   * @throws de.greluc.homeinv.platform.NotFoundException when the tenant has no such live tag
   * @throws TagNameTakenException when another live tag already carries the new name
   */
  TagView update(UUID tagId, UpdateTagCommand command, UUID actor);

  /**
   * Merges one tag into another: every assignment moves, and the source becomes a tombstone.
   *
   * <p>The source is not deleted. A client holding its id — a saved search, an open page, a synced
   * device — is redirected to what it became rather than answered "not found", and the audit log
   * reads correctly afterwards (REQ-CORE-063).
   *
   * <p>Where both tags were on the same thing, the duplicate is dropped rather than the merge
   * failing: the two were being used for one idea, which is why they are being merged.
   *
   * @param sourceId the tag that disappears
   * @param targetId the tag that absorbs it
   * @param actor the authenticated user
   * @return the target, with its assignments now including the source's
   * @throws de.greluc.homeinv.platform.NotFoundException when either tag is not visible
   * @throws IllegalArgumentException when the two are the same tag
   */
  TagView merge(UUID sourceId, UUID targetId, UUID actor);

  /**
   * Puts a tag on an item or a place.
   *
   * <p>Idempotent: assigning a tag that is already there changes nothing and raises nothing, because
   * a client retrying a request it never saw the answer to must not be told it failed.
   *
   * <p>Where the tag belongs to an <b>exclusive</b> group, any other tag of that group is removed
   * from the same target first: "condition" holds one of new, used or broken, and a thing that was
   * both would be a thing whose condition nobody can read.
   *
   * @param tagId the tag
   * @param target what kind of thing it goes on
   * @param targetId the item or place
   * @param actor the authenticated user
   * @throws de.greluc.homeinv.platform.NotFoundException when the tag or the target is not visible
   */
  void assign(UUID tagId, TagTarget target, UUID targetId, UUID actor);

  /**
   * Takes a tag off an item or a place.
   *
   * <p>Idempotent for the same reason {@link #assign} is.
   *
   * @param tagId the tag
   * @param target what kind of thing it is on
   * @param targetId the item or place
   * @param actor the authenticated user
   */
  void unassign(UUID tagId, TagTarget target, UUID targetId, UUID actor);

  /**
   * The tags one thing carries.
   *
   * <p>Paged like every other collection (REQ-NFR-010), and a thing normally carries a handful: the
   * first page is the whole answer. The bound is there because "normally" is not a guarantee — an
   * import can put fifty tags on one row, and an endpoint that returns whatever it finds is one
   * whose worst case nobody measured.
   *
   * @param target what kind of thing
   * @param targetId the item or place
   * @param cursor an opaque cursor from a previous page, or {@code null} for the first
   * @param limit how many at most; capped at 200
   * @return the page and a cursor for the next one
   */
  TagPage tagsOf(TagTarget target, UUID targetId, String cursor, int limit);

  /**
   * Creates a group of tags.
   *
   * @param command the key, labels and whether its tags exclude one another
   * @param actor the authenticated user
   * @return the group
   * @throws TagNameTakenException when the tenant already has a group with that key
   */
  TagGroupView createGroup(CreateTagGroupCommand command, UUID actor);

  /**
   * One page of the tenant's tag groups.
   *
   * @param cursor an opaque cursor from a previous page, or {@code null} for the first
   * @param limit how many at most; capped at 200
   * @return the page and a cursor for the next one
   */
  TagGroupPage groups(String cursor, int limit);

  /** What a tag can be attached to. */
  enum TagTarget {
    /** An item. */
    ITEM,
    /** A place in the location tree. */
    LOCATION
  }

  /**
   * What to call a new tag.
   *
   * @param name the name a person reads and types; unique per tenant, case-insensitively
   * @param groupId the group it belongs to, or {@code null}
   * @param colour {@code #rrggbb}, or {@code null}
   * @param icon an icon name for clients, or {@code null}
   */
  record CreateTagCommand(String name, UUID groupId, String colour, String icon) {}

  /**
   * What may change about a tag.
   *
   * @param name the new name
   * @param groupId the new group, or {@code null} to take it out of one
   * @param colour the new colour, or {@code null}
   * @param icon the new icon, or {@code null}
   */
  record UpdateTagCommand(String name, UUID groupId, String colour, String icon) {}

  /**
   * What to call a new group.
   *
   * @param key the stable key, unique per tenant
   * @param labels the group's name per language tag
   * @param exclusive whether a thing may carry only one tag of this group
   * @param displayOrder where the group sits among the others
   */
  record CreateTagGroupCommand(
      String key, Map<String, String> labels, boolean exclusive, int displayOrder) {}

  /**
   * One page of tags.
   *
   * @param items the tags on this page
   * @param nextCursor the cursor for the next page, or {@code null} when this was the last
   */
  record TagPage(List<TagView> items, String nextCursor) {}

  /**
   * One page of tag groups.
   *
   * @param items the groups on this page
   * @param nextCursor the cursor for the next page, or {@code null} when this was the last
   */
  record TagGroupPage(List<TagGroupView> items, String nextCursor) {}

  /** Thrown when a tag name, or a group key, is already in use by this tenant. */
  class TagNameTakenException extends RuntimeException {

    private final String name;

    /**
     * Names what is taken, because that is what the caller has to change.
     *
     * @param what the kind of thing — a tag, a group
     * @param name the name or key
     */
    public TagNameTakenException(String what, String name) {
      super("This tenant already has a " + what + " called '" + name + "'.");
      this.name = name;
    }

    /**
     * The name that is taken.
     *
     * @return the name
     */
    public String getName() {
      return name;
    }
  }
}
