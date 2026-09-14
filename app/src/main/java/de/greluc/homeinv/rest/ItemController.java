/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import de.greluc.homeinv.platform.Page;
import de.greluc.homeinv.audit.api.RevisionLog;
import de.greluc.homeinv.inventory.api.BulkItemOperations;
import de.greluc.homeinv.inventory.api.ItemBundles;
import de.greluc.homeinv.inventory.api.ItemRelations;
import de.greluc.homeinv.authorization.api.Permission;
import de.greluc.homeinv.authorization.api.RequiresPermission;
import de.greluc.homeinv.identity.api.AuthenticatedUser;
import de.greluc.homeinv.inventory.api.ItemView;
import de.greluc.homeinv.inventory.api.Valuation;
import de.greluc.homeinv.platform.Money;
import de.greluc.homeinv.inventory.api.ItemService;
import de.greluc.homeinv.inventory.api.ItemKind;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotEmpty;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
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
  private final BulkItemOperations bulk;

  /**
   * The mapper the request body is re-serialised with, for an {@code Idempotency-Key}'s hash.
   *
   * <p>The application's own, so what is hashed is what this service would have written: a second
   * mapper configured differently would make the same request hash two ways.
   */
  private final tools.jackson.databind.ObjectMapper json;

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
      @Valid @RequestBody CreateItemRequest request,
      HttpServletRequest http,
      @AuthenticationPrincipal AuthenticatedUser user) {
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
                request.minimumStock(),
                ValuationRequest.asValuation(request.valuation())),
            IdempotencyKeys.from(http, request, json),
            user.userId());

    ItemView view = result.item();
    if (!result.created()) {
      return ResponseEntity.ok(view);
    }
    return ResponseEntity.created(URI.create("/api/v1/items/" + view.id())).body(view);
  }

  /**
   * Applies one change to many items (REQ-CORE-011).
   *
   * <p>Up to 500 entries, each with an outcome of its own. The status code says whether anything
   * failed and the body says what: {@code 200} when every entry was applied, {@code 207} when at
   * least one was not (08 §8.2, decided with the owner 2026-09-13). A client that only wants to
   * know whether to re-read its list reads the code; one that wants to tell a person what happened
   * reads the body.
   *
   * <p>The permission on this handler is deliberately the weakest one: the path serves four
   * operations and an annotation is a constant, so what actually gates the change is the
   * application layer, which requires {@code inventory:item:update},
   * {@code tagging:tag:assign} or {@code inventory:item:delete} depending on the operation
   * (ADR-0010).
   *
   * @param request the operation, its target and the items
   * @param user the authenticated caller
   * @return one status line per entry, in the order they were sent
   */
  @PostMapping(path = "/bulk", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.ITEM_READ)
  @ApiResponse(
      responseCode = "200",
      description = "Every entry was applied.",
      content = @Content(schema = @Schema(implementation = BulkResponse.class)))
  @ApiResponse(
      responseCode = "207",
      description = "At least one entry was not applied; each entry says what became of it.",
      content = @Content(schema = @Schema(implementation = BulkResponse.class)))
  public ResponseEntity<BulkResponse> bulkChangeItems(
      @Valid @RequestBody BulkRequest request, @AuthenticationPrincipal AuthenticatedUser user) {

    BulkItemOperations.BulkCommand command =
        new BulkItemOperations.BulkCommand(
            request.operation(),
            request.locationId(),
            request.tagId(),
            request.itemTypeId(),
            request.entries().stream().map(entry -> asEntry(request, entry)).toList());

    BulkItemOperations.BulkOutcome outcome = bulk.apply(command, user.userId());
    BulkResponse body =
        new BulkResponse(outcome.entries().stream().map(ItemController::statusLine).toList());
    return ResponseEntity.status(outcome.complete() ? HttpStatus.OK : HttpStatus.MULTI_STATUS)
        .body(body);
  }

  /**
   * Turns one requested entry into the one the application layer takes.
   *
   * @param request the whole call, for what the entry's key is spent on
   * @param entry the entry as it arrived
   * @return the entry, with its version and key in their absent-value forms
   */
  private BulkItemOperations.Entry asEntry(BulkRequest request, BulkEntryRequest entry) {
    return new BulkItemOperations.Entry(
        entry.itemId(),
        entry.version() == null ? OptionalLong.empty() : OptionalLong.of(entry.version()),
        entry.idempotencyKey() == null || entry.idempotencyKey().isBlank()
            ? Optional.empty()
            : Optional.of(
                IdempotencyKeys.of(
                    entry.idempotencyKey(),
                    // What the key is spent on: this operation, this target, this
                    // item. The same key sent later for a different change is then
                    // a conflict rather than a change nobody asked for twice.
                    new BulkEntryFingerprint(
                        request.operation().name(),
                        request.locationId(),
                        request.tagId(),
                        request.itemTypeId(),
                        entry.itemId()),
                    json)));
  }

  /**
   * One entry's outcome as a status line.
   *
   * <p>The fields are those of an RFC 9457 problem, so a client already parsing errors can parse
   * these too. What they are not is a problem document: the response as a whole succeeded, the
   * media type is {@code application/json}, and there is one {@code traceId} for the request rather
   * than one per entry.
   *
   * @param outcome what the application layer reported
   * @return the line for this entry
   */
  private static BulkEntryStatus statusLine(BulkItemOperations.EntryOutcome outcome) {
    if (outcome.applied()) {
      return new BulkEntryStatus(outcome.itemId(), HttpStatus.OK.value(), null, null, null);
    }
    ProblemType type = problemFor(outcome.failure());
    return new BulkEntryStatus(
        outcome.itemId(),
        type.status().value(),
        type.uri().toString(),
        type.title(),
        detailFor(outcome.failure(), type));
  }

  /**
   * Which registered condition an entry's failure is.
   *
   * <p>Six cases, and they are the six these four operations can raise. This is not a second copy
   * of {@link ApiExceptionHandler}: that advice answers a whole request and has a
   * {@code HttpServletRequest} to build an {@code instance} from, and an entry has neither.
   *
   * @param failure what the entry raised
   * @return the type whose status and title describe it
   */
  private static ProblemType problemFor(RuntimeException failure) {
    return switch (failure) {
      case de.greluc.homeinv.platform.NotFoundException ignored -> ProblemType.NOT_FOUND;
      case de.greluc.homeinv.authorization.api.AccessDeniedException ignored ->
          ProblemType.FORBIDDEN;
      case de.greluc.homeinv.platform.StaleVersionException ignored ->
          ProblemType.PRECONDITION_FAILED;
      case de.greluc.homeinv.catalog.api.InvalidAttributesException ignored ->
          ProblemType.VALIDATION_FAILED;
      case de.greluc.homeinv.idempotency.api.IdempotencyKeyConflictException ignored ->
          ProblemType.IDEMPOTENCY_KEY_CONFLICT;
      case IllegalArgumentException ignored -> ProblemType.VALIDATION_FAILED;
      default -> ProblemType.INTERNAL_ERROR;
    };
  }

  /**
   * What the entry's line says in prose.
   *
   * <p>Never the exception of a failure nobody planned for: that text can carry a class name, a
   * path or a fragment of SQL, and 08 §8.2 forbids all three in an error. The log line has it,
   * and the response has the {@code traceId} that finds the log line.
   *
   * @param failure what the entry raised
   * @param type what it was classified as
   * @return the detail for this entry's line
   */
  private static String detailFor(RuntimeException failure, ProblemType type) {
    if (type == ProblemType.INTERNAL_ERROR) {
      return Problems.INTERNAL_DETAIL;
    }
    if (failure instanceof de.greluc.homeinv.platform.NotFoundException absent) {
      return "No such " + absent.getResource() + " is visible to you.";
    }
    if (failure instanceof de.greluc.homeinv.authorization.api.AccessDeniedException) {
      return "Your role does not permit this operation.";
    }
    return failure.getMessage();
  }

  /**
   * One change, its target, and the items it applies to.
   *
   * @param operation which of the four
   * @param locationId where a move puts them
   * @param tagId what a tag assignment assigns
   * @param itemTypeId the type a change of type writes them against
   * @param entries the items, at most 500
   */
  public record BulkRequest(
      @NotNull BulkItemOperations.Operation operation,
      UUID locationId,
      UUID tagId,
      UUID itemTypeId,
      @NotEmpty @Size(min = 1, max = 500) List<@Valid BulkEntryRequest> entries) {}

  /**
   * One item in a bulk call.
   *
   * @param itemId the item
   * @param version the version the caller acted on, from the {@code ETag} of its last read, or
   *     {@code null} to skip the check for this entry. In the body and not in {@code If-Match},
   *     because a header cannot carry 500 of them
   * @param idempotencyKey this entry's own key, or {@code null}. In the body for the same reason
   */
  public record BulkEntryRequest(
      @NotNull UUID itemId,
      @PositiveOrZero Long version,
      @Size(max = 255) String idempotencyKey) {}

  /**
   * What an entry's idempotency key is spent on.
   *
   * <p>Hashed, never sent or stored as it stands. It exists so that one key cannot be spent moving
   * an item and then accepted as having deleted it.
   *
   * @param operation the operation's name
   * @param locationId a move's target, or null
   * @param tagId a tag assignment's target, or null
   * @param itemTypeId a change of type's target, or null
   * @param itemId the item this entry names
   */
  private record BulkEntryFingerprint(
      String operation, UUID locationId, UUID tagId, UUID itemTypeId, UUID itemId) {}

  /**
   * What became of every entry.
   *
   * @param entries one line per entry, in the order they were sent
   */
  public record BulkResponse(List<BulkEntryStatus> entries) {}

  /**
   * What became of one entry.
   *
   * @param itemId the item this line is about
   * @param status the status this entry would have had as a request of its own
   * @param type the problem type URI, or {@code null} when the entry was applied
   * @param title the stable name of the condition, or {@code null} when the entry was applied
   * @param detail what went wrong in prose, or {@code null} when nothing did
   */
  public record BulkEntryStatus(
      UUID itemId, int status, String type, String title, String detail) {}

  /**
   * Reads one item.
   *
   * @param id the item
   * @return the item
   */
  @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.ITEM_READ)
  @CanFail(ProblemType.NOT_FOUND)
  public ResponseEntity<ItemView> getItem(@PathVariable UUID id) {
    // The ETag a write has to send back (REQ-API-004). The version and not a hash
    // of the body: the body is redacted per caller, so a hash would differ between
    // two people looking at the same unchanged row.
    ItemView view = items.get(id);
    return ResponseEntity.ok().eTag(EntityTags.of(view.version())).body(view);
  }

  /**
   * Replaces the mutable fields of an item.
   *
   * <p>Requires {@code If-Match} (REQ-API-004). Editing the same thing from two screens is the
   * case the header exists for, and an inventory is edited by a household.
   *
   * @param id the item
   * @param request the new values
   * @param http the request, for its {@code If-Match}
   * @param user the authenticated caller
   * @return the changed item, with its new entity tag
   */
  @PutMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.ITEM_UPDATE)
  @CanFail({
    ProblemType.NOT_FOUND,
    ProblemType.PRECONDITION_REQUIRED,
    ProblemType.PRECONDITION_FAILED
  })
  public ResponseEntity<ItemView> updateItem(
      @PathVariable UUID id,
      @Valid @RequestBody UpdateItemRequest request,
      HttpServletRequest http,
      @AuthenticationPrincipal AuthenticatedUser user) {
    ItemView view =
        items.update(
            id,
            new ItemService.UpdateItemCommand(
                request.name(),
                request.description(),
                request.locationId(),
                request.quantity(),
                request.quantityUnit(),
                request.attributes(),
                request.notes(),
                request.minimumStock(),
                ValuationRequest.asValuation(request.valuation())),
            EntityTags.required(http),
            user.userId());
    return ResponseEntity.ok().eTag(EntityTags.of(view.version())).body(view);
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
  @CanFail({ProblemType.NOT_FOUND, ProblemType.PRECONDITION_REQUIRED, ProblemType.PRECONDITION_FAILED})
  public void deleteItem(
      @PathVariable UUID id,
      HttpServletRequest http,
      @AuthenticationPrincipal AuthenticatedUser user) {
    items.delete(id, EntityTags.required(http), user.userId());
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
  public Page<ItemRelations.RelationView> itemRelations(
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
    relations.unrelate(id, relationId, user.userId());
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
  public Page<ItemBundles.BundleMemberView> bundleContents(
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
  public Page<ItemBundles.BundleMemberView> bundlesContaining(
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
  public Page<ItemView> trashedItems(
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
  @CanFail({
    ProblemType.NOT_FOUND,
    ProblemType.VALIDATION_FAILED,
    ProblemType.PRECONDITION_REQUIRED,
    ProblemType.PRECONDITION_FAILED
  })
  public ResponseEntity<ItemView> restoreItem(
      @PathVariable UUID id,
      HttpServletRequest http,
      @AuthenticationPrincipal AuthenticatedUser user) {
    ItemView view = items.restore(id, EntityTags.required(http), user.userId());
    return ResponseEntity.ok().eTag(EntityTags.of(view.version())).body(view);
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
  @CanFail({
    ProblemType.NOT_FOUND,
    ProblemType.VALIDATION_FAILED,
    ProblemType.PRECONDITION_REQUIRED,
    ProblemType.PRECONDITION_FAILED
  })
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void purgeItem(
      @PathVariable UUID id,
      HttpServletRequest http,
      @AuthenticationPrincipal AuthenticatedUser user) {
    items.purge(id, EntityTags.required(http), user.userId());
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
  public Page<RevisionLog.RevisionView> itemRevisions(
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
   * @param http the request, for its {@code If-Match}
   * @param user the authenticated caller
   * @return the item in its restored state, with its new entity tag
   */
  @PostMapping(
      path = "/{id}/revisions/{revision}/restore",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.ITEM_UPDATE)
  @CanFail({
    ProblemType.NOT_FOUND,
    ProblemType.VALIDATION_FAILED,
    ProblemType.PRECONDITION_REQUIRED,
    ProblemType.PRECONDITION_FAILED
  })
  public ResponseEntity<ItemView> restoreItemRevision(
      @PathVariable UUID id,
      @PathVariable long revision,
      HttpServletRequest http,
      @AuthenticationPrincipal AuthenticatedUser user) {
    ItemView view =
        items.restoreRevision(id, revision, EntityTags.required(http), user.userId());
    return ResponseEntity.ok().eTag(EntityTags.of(view.version())).body(view);
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
      @PositiveOrZero BigDecimal minimumStock,
      @Valid ValuationRequest valuation) {}

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
      @PositiveOrZero BigDecimal minimumStock,
      @Valid ValuationRequest valuation) {}

  /**
   * What an item cost, what covers it and what replacing it would cost (REQ-LIFE-001/002/014).
   *
   * <p>Replaced whole rather than merged, like the attributes and for the same reason: a figure
   * left out is one deliberately cleared, and under a merge "remove the purchase price" would have
   * no spelling at all.
   *
   * <p>Each amount is {@code {"amount":"49.90","currency":"EUR"}} — a string, never a JSON number,
   * because a number is a {@code double} by the time it has been through a browser.
   *
   * @param purchase what it cost, or omitted
   * @param purchasedOn when it was bought, or omitted
   * @param purchaseSource where from — a shop, a person, a listing
   * @param warrantyUntil when the warranty ends, or omitted
   * @param lifetimeWarranty whether it is covered for life, in which case {@code warrantyUntil}
   *     must be omitted: both is two answers to one question and is refused
   * @param replacement what replacing it would cost, or omitted
   * @param replacementAsOf the day that figure was true
   * @param replacementSource {@code MANUAL} or {@code PLUGIN}
   * @param currentValue what it is worth now, or omitted
   * @param currentValueAsOf the day that figure was true
   */
  public record ValuationRequest(
      Money purchase,
      LocalDate purchasedOn,
      @Size(max = 300) String purchaseSource,
      LocalDate warrantyUntil,
      boolean lifetimeWarranty,
      Money replacement,
      LocalDate replacementAsOf,
      Valuation.Provenance replacementSource,
      Money currentValue,
      LocalDate currentValueAsOf) {

    /**
     * The figures as the domain takes them.
     *
     * @param request the body's valuation, or {@code null} when it carried none
     * @return the valuation, {@link Valuation#NONE} for an absent one
     */
    static Valuation asValuation(ValuationRequest request) {
      if (request == null) {
        return Valuation.NONE;
      }
      return new Valuation(
          request.purchase(),
          request.purchasedOn(),
          request.purchaseSource(),
          request.warrantyUntil(),
          request.lifetimeWarranty(),
          request.replacement(),
          request.replacementAsOf(),
          request.replacementSource(),
          request.currentValue(),
          request.currentValueAsOf());
    }
  }
}
