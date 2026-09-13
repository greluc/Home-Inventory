/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import de.greluc.homeinv.authorization.api.Permission;
import de.greluc.homeinv.authorization.api.RequiresPermission;
import de.greluc.homeinv.identity.api.AuthenticatedUser;
import de.greluc.homeinv.tagging.api.TagGroupView;
import de.greluc.homeinv.tagging.api.TagService;
import de.greluc.homeinv.tagging.api.TagView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.net.URI;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The tag endpoints (REQ-CORE-060…063).
 *
 * <p>Two shapes of path, deliberately. {@code /api/v1/tags} is the vocabulary — creating, renaming
 * and merging tags — and {@code /api/v1/items/{id}/tags} is what one thing carries. They sit in one
 * controller because they are one block's surface; a reader looking for "how does a tag get onto an
 * item" finds both here rather than in whichever controller happened to own the noun.
 */
@RestController
@RequiredArgsConstructor
public class TagController {

  private final TagService tags;

  /**
   * One page of the tenant's tags.
   *
   * @param cursor an opaque cursor from a previous page, or omitted for the first
   * @param limit how many at most; capped at 200 by the service
   * @return the page and a cursor for the next one
   */
  @GetMapping(path = "/api/v1/tags", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.TAG_READ)
  @CanFail(ProblemType.MALFORMED_REQUEST)
  public TagService.TagPage listTags(
      @RequestParam(required = false) @Size(max = 500) String cursor,
      @RequestParam(required = false, defaultValue = "50") @Positive @Max(200) int limit) {
    return tags.tags(cursor, limit);
  }

  /**
   * Creates a tag.
   *
   * @param request the name, optional group, colour and icon
   * @param user the authenticated caller
   * @return the new tag
   */
  @PostMapping(path = "/api/v1/tags", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.TAG_CREATE)
  @CanFail({ProblemType.NAME_TAKEN, ProblemType.VALIDATION_FAILED, ProblemType.NOT_FOUND})
  @ResponseStatus(HttpStatus.CREATED)
  public ResponseEntity<TagView> createTag(
      @Valid @RequestBody TagRequest request, @AuthenticationPrincipal AuthenticatedUser user) {
    TagView view =
        tags.create(
            new TagService.CreateTagCommand(
                request.name(), request.groupId(), request.colour(), request.icon()),
            user.userId());
    return ResponseEntity.created(URI.create("/api/v1/tags/" + view.id())).body(view);
  }

  /**
   * Renames a tag, recolours it, or moves it between groups.
   *
   * @param id the tag
   * @param request the new state
   * @param user the authenticated caller
   * @return the changed tag
   */
  @PutMapping(path = "/api/v1/tags/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.TAG_UPDATE)
  @CanFail({ProblemType.NOT_FOUND, ProblemType.NAME_TAKEN, ProblemType.VALIDATION_FAILED})
  public TagView updateTag(
      @PathVariable UUID id,
      @Valid @RequestBody TagRequest request,
      @AuthenticationPrincipal AuthenticatedUser user) {
    return tags.update(
        id,
        new TagService.UpdateTagCommand(
            request.name(), request.groupId(), request.colour(), request.icon()),
        user.userId());
  }

  /**
   * Merges one tag into another (REQ-CORE-063).
   *
   * <p>{@code POST} rather than {@code DELETE}: this is not a deletion. The source becomes a
   * tombstone pointing at the target, every assignment moves, and a client holding the old id is
   * redirected rather than told it never existed.
   *
   * @param id the tag that disappears
   * @param targetId the tag that absorbs it
   * @param user the authenticated caller
   * @return the target, now carrying both sets of assignments
   */
  @PostMapping(
      path = "/api/v1/tags/{id}/merge-into/{targetId}",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.TAG_UPDATE)
  @CanFail({ProblemType.NOT_FOUND, ProblemType.VALIDATION_FAILED})
  public TagView mergeTag(
      @PathVariable UUID id,
      @PathVariable UUID targetId,
      @AuthenticationPrincipal AuthenticatedUser user) {
    return tags.merge(id, targetId, user.userId());
  }

  /**
   * One page of the tenant's tag groups.
   *
   * @param cursor an opaque cursor from a previous page, or omitted for the first
   * @param limit how many at most; capped at 200 by the service
   * @return the page and a cursor for the next one
   */
  @GetMapping(path = "/api/v1/tag-groups", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.TAG_READ)
  @CanFail(ProblemType.MALFORMED_REQUEST)
  public TagService.TagGroupPage listGroups(
      @RequestParam(required = false) @Size(max = 500) String cursor,
      @RequestParam(required = false, defaultValue = "50") @Positive @Max(200) int limit) {
    return tags.groups(cursor, limit);
  }

  /**
   * Creates a tag group.
   *
   * @param request the key, labels, exclusivity and order
   * @param user the authenticated caller
   * @return the new group
   */
  @PostMapping(path = "/api/v1/tag-groups", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.TAG_CREATE)
  @CanFail({ProblemType.NAME_TAKEN, ProblemType.VALIDATION_FAILED})
  @ResponseStatus(HttpStatus.CREATED)
  public ResponseEntity<TagGroupView> createGroup(
      @Valid @RequestBody TagGroupRequest request,
      @AuthenticationPrincipal AuthenticatedUser user) {
    TagGroupView view =
        tags.createGroup(
            new TagService.CreateTagGroupCommand(
                request.key(), request.labels(), request.exclusive(), request.displayOrder()),
            user.userId());
    return ResponseEntity.created(URI.create("/api/v1/tag-groups/" + view.id())).body(view);
  }

  /**
   * The tags one item carries.
   *
   * @param id the item
   * @param cursor an opaque cursor from a previous page, or omitted for the first
   * @param limit how many at most; capped at 200 by the service
   * @return the page and a cursor for the next one
   */
  @GetMapping(path = "/api/v1/items/{id}/tags", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.TAG_READ)
  @CanFail({ProblemType.NOT_FOUND, ProblemType.MALFORMED_REQUEST})
  public TagService.TagPage itemTags(
      @PathVariable UUID id,
      @RequestParam(required = false) @Size(max = 500) String cursor,
      @RequestParam(required = false, defaultValue = "50") @Positive @Max(200) int limit) {
    return tags.tagsOf(TagService.TagTarget.ITEM, id, cursor, limit);
  }

  /**
   * Puts a tag on an item.
   *
   * <p>{@code PUT} because it is idempotent: the same request twice leaves the same one assignment,
   * which is what a client retrying a request it never saw the answer to needs.
   *
   * @param id the item
   * @param tagId the tag
   * @param user the authenticated caller
   */
  @PutMapping(path = "/api/v1/items/{id}/tags/{tagId}")
  @RequiresPermission(Permission.TAG_ASSIGN)
  @CanFail(ProblemType.NOT_FOUND)
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void tagItem(
      @PathVariable UUID id,
      @PathVariable UUID tagId,
      @AuthenticationPrincipal AuthenticatedUser user) {
    tags.assign(tagId, TagService.TagTarget.ITEM, id, user.userId());
  }

  /**
   * Takes a tag off an item.
   *
   * @param id the item
   * @param tagId the tag
   * @param user the authenticated caller
   */
  @DeleteMapping(path = "/api/v1/items/{id}/tags/{tagId}")
  @RequiresPermission(Permission.TAG_ASSIGN)
  @CanFail(ProblemType.NOT_FOUND)
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void untagItem(
      @PathVariable UUID id,
      @PathVariable UUID tagId,
      @AuthenticationPrincipal AuthenticatedUser user) {
    tags.unassign(tagId, TagService.TagTarget.ITEM, id, user.userId());
  }

  /**
   * The tags one place carries.
   *
   * @param id the place
   * @param cursor an opaque cursor from a previous page, or omitted for the first
   * @param limit how many at most; capped at 200 by the service
   * @return the page and a cursor for the next one
   */
  @GetMapping(path = "/api/v1/locations/{id}/tags", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.TAG_READ)
  @CanFail({ProblemType.NOT_FOUND, ProblemType.MALFORMED_REQUEST})
  public TagService.TagPage locationTags(
      @PathVariable UUID id,
      @RequestParam(required = false) @Size(max = 500) String cursor,
      @RequestParam(required = false, defaultValue = "50") @Positive @Max(200) int limit) {
    return tags.tagsOf(TagService.TagTarget.LOCATION, id, cursor, limit);
  }

  /**
   * Puts a tag on a place.
   *
   * @param id the place
   * @param tagId the tag
   * @param user the authenticated caller
   */
  @PutMapping(path = "/api/v1/locations/{id}/tags/{tagId}")
  @RequiresPermission(Permission.TAG_ASSIGN)
  @CanFail(ProblemType.NOT_FOUND)
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void tagLocation(
      @PathVariable UUID id,
      @PathVariable UUID tagId,
      @AuthenticationPrincipal AuthenticatedUser user) {
    tags.assign(tagId, TagService.TagTarget.LOCATION, id, user.userId());
  }

  /**
   * Takes a tag off a place.
   *
   * @param id the place
   * @param tagId the tag
   * @param user the authenticated caller
   */
  @DeleteMapping(path = "/api/v1/locations/{id}/tags/{tagId}")
  @RequiresPermission(Permission.TAG_ASSIGN)
  @CanFail(ProblemType.NOT_FOUND)
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void untagLocation(
      @PathVariable UUID id,
      @PathVariable UUID tagId,
      @AuthenticationPrincipal AuthenticatedUser user) {
    tags.unassign(tagId, TagService.TagTarget.LOCATION, id, user.userId());
  }

  /**
   * The body of a tag creation or change.
   *
   * @param name the name a person reads and types
   * @param groupId the group it belongs to, or omitted
   * @param colour {@code #rrggbb}, or omitted
   * @param icon an icon name for clients, or omitted
   */
  public record TagRequest(
      @NotBlank @Size(max = 120) String name,
      UUID groupId,
      @Size(max = 7) String colour,
      @Size(max = 64) String icon) {}

  /**
   * The body of a group creation.
   *
   * @param key the stable key
   * @param labels the group's name per language tag
   * @param exclusive whether a thing may carry only one tag of this group
   * @param displayOrder where the group sits among the others
   */
  public record TagGroupRequest(
      @NotBlank @Size(max = 64) String key,
      Map<String, String> labels,
      boolean exclusive,
      int displayOrder) {}
}
