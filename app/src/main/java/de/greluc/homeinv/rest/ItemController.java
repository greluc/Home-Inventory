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
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
  @PostMapping
  @RequiresPermission(Permission.ITEM_CREATE)
  public ResponseEntity<ItemView> create(
      @Valid @RequestBody CreateItemRequest request, @AuthenticationPrincipal AuthenticatedUser user) {
    ItemService.CreateResult result =
        items.create(
            new ItemService.CreateItemCommand(
                request.id(),
                request.itemTypeVersionId(),
                request.name(),
                request.description(),
                request.kind(),
                request.locationId(),
                request.quantity(),
                request.quantityUnit()),
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
  @GetMapping("/{id}")
  @RequiresPermission(Permission.ITEM_READ)
  public ItemView get(@PathVariable UUID id) {
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
  @PutMapping("/{id}")
  @RequiresPermission(Permission.ITEM_UPDATE)
  public ItemView update(
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
            request.quantityUnit()),
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
   * @return an empty {@code 204}
   */
  @DeleteMapping("/{id}")
  @RequiresPermission(Permission.ITEM_DELETE)
  public ResponseEntity<Void> delete(
      @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser user) {
    items.delete(id, user.userId());
    return ResponseEntity.noContent().build();
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
   * @param itemTypeVersionId the type version, or omitted — which is what a stage-0 client does,
   *     because there is no type system to choose from and the server fills in the built-in type
   * @param name the name
   * @param description free text
   * @param kind {@code PHYSICAL} or {@code DIGITAL}
   * @param locationId required for a physical item
   * @param quantity how many; {@code null} means one
   * @param quantityUnit the unit
   */
  public record CreateItemRequest(
      UUID id,
      UUID itemTypeVersionId,
      @NotBlank @Size(max = 500) String name,
      @Size(max = 20_000) String description,
      @NotNull ItemKind kind,
      UUID locationId,
      @PositiveOrZero BigDecimal quantity,
      @Size(max = 30) String quantityUnit) {}

  /**
   * The body of an update. {@code kind} is absent: a physical item does not become a digital one.
   *
   * @param name the new name
   * @param description the new description
   * @param locationId the new location; required while the item is physical
   * @param quantity the new quantity; {@code null} means one
   * @param quantityUnit the new unit
   */
  public record UpdateItemRequest(
      @NotBlank @Size(max = 500) String name,
      @Size(max = 20_000) String description,
      UUID locationId,
      @PositiveOrZero BigDecimal quantity,
      @Size(max = 30) String quantityUnit) {}
}
