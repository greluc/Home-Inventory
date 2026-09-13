/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import de.greluc.homeinv.audit.api.RevisionLog;
import de.greluc.homeinv.inventory.api.ItemBundles;
import de.greluc.homeinv.inventory.api.ItemRelations;
import de.greluc.homeinv.authorization.api.Permission;
import de.greluc.homeinv.authorization.api.RequiresPermission;
import de.greluc.homeinv.identity.api.AuthenticatedUser;
import de.greluc.homeinv.inventory.api.ItemView;
import de.greluc.homeinv.inventory.api.ItemService;
import de.greluc.homeinv.inventory.api.ItemKind;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The {@code /api/v1/items} endpoints.
 *
 * <p>An adapter and nothing more. It turns HTTP into a call on {@link ItemService} and a result
 * back into HTTP; it makes no authorization decision and holds no rule of its own (ADR-0010,
 * REQ-SEC-022…028). A rule enforced here would be a second place to keep right, and the second
 * place is always the one that gets forgotten when a new surface — GraphQL, gRPC, a bulk import —
 * is added.
 *
 * <p>The tenant never appears in a signature here. It comes from the session, through
 * {@code TenantContext}, and a path or header carrying it would be a value the caller chooses.
 */
@RestController
@RequestMapping("/api/v1/items")
@RequiredArgsConstructor
public class ItemController {

  private final ItemService items;
  private final ItemRelations relations;
  private final ItemBundles bundles;

  /**
   * Creates an item, or returns the one that is already there.
   *
   * <p>{@code 201} when this request created it, {@code 200} when an identical creation had already
   * happened — which is what makes a client's retry safe (REQ-CORE-001). A different item under the
   * same id is a {@code 409}.
   *
   * @param request the item to create
   * @param user the authenticated caller
   * @return the created or already-present item
   */
  @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.ITEM_CREATE)
  @CanFail(ProblemType.RESOURCE_EXISTS)
  // The one endpoint with two success codes, so the second one is written down:
  // springdoc derives a single response from the return type and cannot see that
  // this method chooses between them.
  @ApiResponse(
      responseCode = "201",
      description = "This request created the item. `Location` names it.",
      content = @Content(schema = @Schema(implementation = ItemView.class)))
  @ApiResponse(
      responseCode = "200",
      description = "An identical item already existed under this id; this is that item.",
      content = @Content(schema = @Schema(implementation = ItemView.class)))
  public ResponseEntity<ItemView> createItem(
      @Valid @RequestBody CreateItemRequest request, @AuthenticationPrincipal AuthenticatedUser user) {
    ItemService.CreateResult result =
        items.create(
            new ItemService.CreateItemCommand(
                request.id(),
                request.itemTypeId(),
                request.name(),
                request.description(),
                request.kind(),
                request.locationId(),
                request.quantity(),
                request.quantityUnit(),
                request.attributes(),
                request.notes(),
                request.minimumStock()),
            user.userId());

    ItemView view = result.item();
    if (!result.created()) {
      return ResponseEntity.ok(view);
    }
    return ResponseEntity.created(URI.create("/api/v1/items/" + view.id())).body(view);
  }

  /**
   * Reads one item.
   *
   * @param id the item
   * @return the item
   */
  @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.ITEM_READ)
  @CanFail(ProblemType.NOT_FOUND)
  public ItemView getItem(@PathVariable UUID id) {
    return items.get(id);
  }

  /**
   * Replaces the mutable fields of an item.
   *
   * @param id the item
   * @param request the new values
   * @param user the authenticated caller
   * @return the changed item
   */
  @PutMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.ITEM_UPDATE)
  @CanFail(ProblemType.NOT_FOUND)
  public ItemView updateItem(
      @PathVariable UUID id,
      @Valid @RequestBody UpdateItemRequest request,
      @AuthenticationPrincipal AuthenticatedUser user) {
    return items.update(
        id,
        new ItemService.UpdateItemCommand(
            request.name(),
            request.description(),
            request.locationId(),
            request.quantity(),
            request.quantityUnit(),
            request.attributes(),
            request.notes(),
            request.minimumStock()),
        user.userId());
  }

  /**
   * Deletes an item, leaving a tombstone.
   *
   * <p>{@code 204} whether or not the item was already deleted: a client retrying a request whose
   * answer it never saw must not be told it failed for succeeding twice.
   *
   * @param id the item
   * @param user the authenticated caller
   */
  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @RequiresPermission(Permission.ITEM_DELETE)
  @CanFail(ProblemType.NOT_FOUND)
  public void deleteItem(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser user) {
    items.delete(id, user.userId());
  }

  /**
   * Every relation this item takes part in, from either end (REQ-CORE-006).
   *
   * @param id the item
   * @param cursor an opaque cursor from a previous page, or omitted for the first
   * @param limit how many at most; capped at 200 by the service
   * @return the page and a cursor for the next one
   */
  @GetMapping(path = "/{id}/relations", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.ITEM_READ)
  @CanFail({ProblemType.NOT_FOUND, ProblemType.MALFORMED_REQUEST})
  public ItemRelations.RelationPage itemRelations(
      @PathVariable UUID id,
      @RequestParam(required = false) @Size(max = 500) String cursor,
      @RequestParam(required = false, defaultValue = "50") @Positive @Max(200) int limit) {
    return relations.relationsOf(id, cursor, limit);
  }

  /**
   * Relates this item to another.
   *
   * @param id the item the relation is stated from
   * @param request what it points at and how
   * @param user the authenticated caller
   * @return the relation
   */
  @PostMapping(path = "/{id}/relations", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.ITEM_UPDATE)
  @CanFail({ProblemType.NOT_FOUND, ProblemType.VALIDATION_FAILED})
  @ResponseStatus(HttpStatus.CREATED)
  public ItemRelations.RelationView relateItem(
      @PathVariable UUID id,
      @Valid @RequestBody RelationRequest request,
      @AuthenticationPrincipal AuthenticatedUser user) {
    return relations.relate(
        id,
        request.targetId(),
        ItemRelations.RelationType.valueOf(request.type().toUpperCase(java.util.Locale.ROOT)),
        user.userId());
  }

  /**
   * Removes a relation.
   *
   * @param id the item, which is in the path so the relation is addressed where it is read
   * @param relationId the relation
   * @param user the authenticated caller
   */
  @DeleteMapping(path = "/{id}/relations/{relationId}")
  @RequiresPermission(Permission.ITEM_UPDATE)
  @CanFail(ProblemType.NOT_FOUND)
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void unrelateItem(
      @PathVariable UUID id,
      @PathVariable UUID relationId,
      @AuthenticationPrincipal AuthenticatedUser user) {
    relations.unrelate(relationId, user.userId());
  }

  /**
   * What this bundle contains (REQ-CORE-007).
   *
   * <p>Direct members only: a bundle inside a bundle is one entry here and answers for its own
   * contents when it is asked, which is what keeps a page bounded however deep the graph goes.
   * Something in the trash is left out — it is restorable, and a list of what is in a box must not
   * offer what is no longer in it.
   *
   * @param id the item that contains
   * @param cursor an opaque cursor from a previous page, or omitted for the first
   * @param limit how many at most; capped at 200 by the service
   * @return the page and a cursor for the next one
   */
  @GetMapping(path = "/{id}/bundle", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.ITEM_READ)
  @CanFail({ProblemType.NOT_FOUND, ProblemType.MALFORMED_REQUEST})
  public ItemBundles.BundleMemberPage bundleContents(
      @PathVariable UUID id,
      @RequestParam(required = false) @Size(max = 500) String cursor,
      @RequestParam(required = false, defaultValue = "50") @Positive @Max(200) int limit) {
    return bundles.contentsOf(id, cursor, limit);
  }

  /**
   * Which bundles this item is in (REQ-CORE-007).
   *
   * <p>A list and not a value: an item may be in the camera bag and in the insured-equipment set at
   * once, and forcing a choice would make one of the two wrong about the same object.
   *
   * @param id the item
   * @param cursor an opaque cursor from a previous page, or omitted for the first
   * @param limit how many at most; capped at 200 by the service
   * @return the page and a cursor for the next one
   */
  @GetMapping(path = "/{id}/bundles", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.ITEM_READ)
  @CanFail({ProblemType.NOT_FOUND, ProblemType.MALFORMED_REQUEST})
  public ItemBundles.BundleMemberPage bundlesContaining(
      @PathVariable UUID id,
      @RequestParam(required = false) @Size(max = 500) String cursor,
      @RequestParam(required = false, defaultValue = "50") @Positive @Max(200) int limit) {
    return bundles.bundlesOf(id, cursor, limit);
  }

  /**
   * Puts an item into this bundle (REQ-CORE-007).
   *
   * <p>It does not move: a bundle is not a place, and the member stays in the location it is kept
   * in. Refused with {@code 409} when it would make the bundle contain itself, through any chain.
   *
   * @param id the item that contains
   * @param request the item to put in it
   * @param user the authenticated caller
   * @return the membership
   */
  @PostMapping(path = "/{id}/bundle", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.ITEM_UPDATE)
  @CanFail({ProblemType.NOT_FOUND, ProblemType.BUNDLE_CYCLE, ProblemType.VALIDATION_FAILED})
  @ResponseStatus(HttpStatus.CREATED)
  public ItemBundles.BundleMemberView addToBundle(
      @PathVariable UUID id,
      @Valid @RequestBody BundleMemberRequest request,
      @AuthenticationPrincipal AuthenticatedUser user) {
    return bundles.add(id, request.memberId(), user.userId());
  }

  /**
   * Takes an item out of this bundle (REQ-CORE-007).
   *
   * <p>Addressed by the pair rather than by the membership's id, because that is how a client holds
   * it: it is looking at a bundle and at a thing in it. Nothing moves here either.
   *
   * @param id the item that contains
   * @param memberId the item to take out
   * @param user the authenticated caller
   */
  @DeleteMapping(path = "/{id}/bundle/{memberId}")
  @RequiresPermission(Permission.ITEM_UPDATE)
  @CanFail(ProblemType.NOT_FOUND)
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void removeFromBundle(
      @PathVariable UUID id,
      @PathVariable UUID memberId,
      @AuthenticationPrincipal AuthenticatedUser user) {
    bundles.remove(id, memberId, user.userId());
  }

  /**
   * The body that puts an item into a bundle.
   *
   * @param memberId the item to put in
   */
  public record BundleMemberRequest(@jakarta.validation.constraints.NotNull UUID memberId) {}

  /**
   * The body of a relation.
   *
   * @param targetId the item this one points at
   * @param type {@code ACCESSORY_OF}, {@code PART_OF}, {@code REPLACEMENT_FOR} or {@code RELATED}
   */
  public record RelationRequest(
      @jakarta.validation.constraints.NotNull UUID targetId,
      @NotBlank @Size(max = 20) String type) {}

  /**
   * One page of the tenant's trashed items (REQ-CORE-009).
   *
   * @param cursor an opaque cursor from a previous page, or omitted for the first
   * @param limit how many at most; capped at 200 by the service
   * @return the page and a cursor for the next one
   */
  @GetMapping(path = "/trash", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.ITEM_READ)
  @CanFail(ProblemType.MALFORMED_REQUEST)
  public ItemService.ItemPage trashedItems(
      @RequestParam(required = false) @Size(max = 500) String cursor,
      @RequestParam(required = false, defaultValue = "50") @Positive @Max(200) int limit) {
    return items.trashed(cursor, limit);
  }

  /**
   * Brings an item back out of the trash.
   *
   * @param id the item
   * @param user the authenticated caller
   * @return the item, active again
   */
  @PostMapping(path = "/{id}/restore", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.ITEM_DELETE)
  @CanFail({ProblemType.NOT_FOUND, ProblemType.VALIDATION_FAILED})
  public ItemView restoreItem(
      @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser user) {
    return items.restore(id, user.userId());
  }

  /**
   * Removes an item for good — the second stage of a deletion, and the irreversible one.
   *
   * <p>Refused for anything that is not already in the trash: a person purges what they have
   * decided to delete, not what they are looking at.
   *
   * @param id the item
   * @param user the authenticated caller
   */
  @DeleteMapping(path = "/{id}/permanently")
  @RequiresPermission(Permission.ITEM_PURGE)
  @CanFail({ProblemType.NOT_FOUND, ProblemType.VALIDATION_FAILED})
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void purgeItem(@PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser user) {
    items.purge(id, user.userId());
  }

  /**
   * One page of an item's history, newest first (REQ-CORE-010).
   *
   * @param id the item
   * @param cursor an opaque cursor from a previous page, or omitted for the first
   * @param limit how many at most; capped at 200 by the service
   * @return the page and a cursor for the next one
   */
  @GetMapping(path = "/{id}/revisions", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.ITEM_READ)
  @CanFail({ProblemType.NOT_FOUND, ProblemType.MALFORMED_REQUEST})
  public RevisionLog.RevisionPage itemRevisions(
      @PathVariable UUID id,
      @RequestParam(required = false) @Size(max = 500) String cursor,
      @RequestParam(required = false, defaultValue = "50") @Positive @Max(200) int limit) {
    return items.history(id, cursor, limit);
  }

  /**
   * Makes an earlier state current again.
   *
   * <p>A new revision rather than a rewind: the history after a restore still says what happened and
   * when, which is what makes it a history rather than a current state with extra steps.
   *
   * @param id the item
   * @param revision which revision to put back
   * @param user the authenticated caller
   * @return the item in its restored state
   */
  @PostMapping(
      path = "/{id}/revisions/{revision}/restore",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.ITEM_UPDATE)
  @CanFail({ProblemType.NOT_FOUND, ProblemType.VALIDATION_FAILED})
  public ItemView restoreItemRevision(
      @PathVariable UUID id,
      @PathVariable long revision,
      @AuthenticationPrincipal AuthenticatedUser user) {
    return items.restoreRevision(id, revision, user.userId());
  }

  /**
   * The body of a creation.
   *
   * <p>The constraints are duplicated from the aggregate on purpose. The aggregate's checks are the
   * truth and hold for every caller; these produce a {@code 422} with a field path, which is what
   * lets a form mark the field instead of showing a sentence. A rule that existed only here would
   * be one a non-HTTP caller could walk past.
   *
   * @param id the client's chosen UUIDv7, or {@code null} to have one assigned
   * @param itemTypeId the type, or omitted — the server then uses the tenant's built-in type, which
   *     is what a quick capture does and what every stage-0 client did. The TYPE and not one of its
   *     versions: a person picks "book", and which version that is today is the server's to know
   *     (REQ-CORE-025)
   * @param name the name
   * @param description free text
   * @param kind {@code PHYSICAL} or {@code DIGITAL}
   * @param locationId required for a physical item
   * @param quantity how many; {@code null} means one
   * @param quantityUnit the unit
   * @param attributes the fields the type declares, as a JSON object. Checked against the version's
   *     schema, and an offending value is a {@code 422} naming its path (REQ-CORE-005)
   * @param notes a paragraph in limited Markdown; HTML is removed before it is stored
   * @param minimumStock the level below which this consumable needs restocking, or omitted
   */
  public record CreateItemRequest(
      UUID id,
      UUID itemTypeId,
      @NotBlank @Size(max = 500) String name,
      @Size(max = 20_000) String description,
      @NotNull ItemKind kind,
      UUID locationId,
      @PositiveOrZero BigDecimal quantity,
      @Size(max = 30) String quantityUnit,
      @Size(max = 65_536) String attributes,
      @Size(max = 20_000) String notes,
      @PositiveOrZero BigDecimal minimumStock) {}

  /**
   * The body of an update. {@code kind} is absent: a physical item does not become a digital one.
   *
   * @param name the new name
   * @param description the new description
   * @param locationId the new location; required while the item is physical
   * @param quantity the new quantity; {@code null} means one
   * @param quantityUnit the new unit
   * @param attributes the new attribute set as a JSON object, checked against the version the item
   *     was written against rather than against whatever the type says today
   * @param notes the new notes; HTML is removed before they are stored
   * @param minimumStock the new restocking level, or omitted to stop tracking one
   */
  public record UpdateItemRequest(
      @NotBlank @Size(max = 500) String name,
      @Size(max = 20_000) String description,
      UUID locationId,
      @PositiveOrZero BigDecimal quantity,
      @Size(max = 30) String quantityUnit,
      @Size(max = 65_536) String attributes,
      @Size(max = 20_000) String notes,
      @PositiveOrZero BigDecimal minimumStock) {}
}
