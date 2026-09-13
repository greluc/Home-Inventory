/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

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
                request.attributes()),
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
            request.attributes()),
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
      @Size(max = 65_536) String attributes) {}

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
   */
  public record UpdateItemRequest(
      @NotBlank @Size(max = 500) String name,
      @Size(max = 20_000) String description,
      UUID locationId,
      @PositiveOrZero BigDecimal quantity,
      @Size(max = 30) String quantityUnit,
      @Size(max = 65_536) String attributes) {}
}
