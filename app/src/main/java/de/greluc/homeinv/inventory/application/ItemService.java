/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.application;

import de.greluc.homeinv.catalog.api.CatalogProvisioning;
import de.greluc.homeinv.inventory.api.ItemView;
import de.greluc.homeinv.inventory.domain.Item;
import de.greluc.homeinv.inventory.domain.ItemKind;
import de.greluc.homeinv.inventory.infrastructure.ItemRepository;
import de.greluc.homeinv.platform.NotFoundException;
import de.greluc.homeinv.platform.TenantContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The use cases for items: create, read, change, delete.
 *
 * <p>This is where the transaction boundary is and where authorization decisions belong. The REST
 * layer above it decides nothing (ADR-0010, REQ-SEC-022…028) — it translates HTTP into a call and a
 * result back into HTTP, and an endpoint that made its own decision would be a second place to keep
 * the rules right.
 *
 * <p>The tenant is taken from {@link TenantContext} and never from an argument. A method signature
 * with a tenant parameter is one a caller can get wrong, and the caller closest to the wire is the
 * one an attacker talks to.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ItemService {

  private final ItemRepository items;
  private final CatalogProvisioning catalog;
  private final Clock clock;

  /**
   * Creates an item, or returns the one that is already there.
   *
   * <p>The id may come from the client, because an offline client creates items without asking
   * (ADR-0016). A repeat of a creation whose answer the client never saw is therefore not an error:
   * the same id with the <em>same</em> content returns the existing item and says so, which is what
   * lets a client retry safely while {@code Idempotency-Key} is still stage 1 (REQ-API-005). The
   * same id with different content is a genuine conflict.
   *
   * @param command what to create
   * @param actor the authenticated user, recorded in the audit columns
   * @return the item and whether this call is what created it, which decides 201 versus 200
   * @throws ItemAlreadyExistsException when the id exists in this tenant with different content
   * @throws IllegalArgumentException when an invariant of {@link Item#create} is violated
   */
  @Transactional
  public CreateResult create(CreateItemCommand command, UUID actor) {
    UUID tenantId = TenantContext.require();
    UUID id = command.id() != null ? command.id() : UUID.randomUUID();

    Optional<Item> existing = items.findAny(tenantId, id);
    if (existing.isPresent()) {
      Item found = existing.get();
      if (!sameContent(found, command)) {
        throw new ItemAlreadyExistsException(id);
      }
      return new CreateResult(toView(found), false);
    }

    // Stage 0 has no configurable type system, so a client has no way to know a
    // type version id and is not asked for one. The tenant's built-in type fills
    // the NOT NULL column the schema carries from the start (O27).
    UUID typeVersionId =
        command.itemTypeVersionId() != null
            ? command.itemTypeVersionId()
            : catalog.builtinItemTypeVersion(tenantId);

    Instant now = Instant.now(clock);
    Item item =
        Item.create(
            id,
            tenantId,
            typeVersionId,
            command.name(),
            command.description(),
            command.kind(),
            command.locationId(),
            command.quantity() != null ? command.quantity() : BigDecimal.ONE,
            command.quantityUnit(),
            actor,
            now);

    items.save(item);
    log.debug("Item {} created in tenant {}", id, tenantId);
    return new CreateResult(toView(item), true);
  }

  /**
   * Whether an existing item already says what the command asks for.
   *
   * <p>Compares the fields a creation sets, and nothing else: timestamps and the version differ by
   * definition on a repeat, and comparing them would make every retry a conflict.
   *
   * @param item the item already stored
   * @param command the creation being attempted again
   * @return {@code true} when the two describe the same item
   */
  private static boolean sameContent(Item item, CreateItemCommand command) {
    BigDecimal wanted = command.quantity() != null ? command.quantity() : BigDecimal.ONE;
    // The type is not compared: at stage 0 the client never sends one, so a repeat
    // would compare a resolved id against null and call every retry a conflict.
    return Objects.equals(item.getName(), command.name())
        && Objects.equals(item.getDescription(), command.description())
        && item.getKind() == command.kind()
        && Objects.equals(item.getLocationId(), command.locationId())
        // compareTo, not equals: BigDecimal.equals is scale-sensitive, so 1 and
        // 1.0 would count as different content and turn a retry into a conflict.
        && item.getQuantity().compareTo(wanted) == 0
        && Objects.equals(item.getQuantityUnit(), command.quantityUnit());
  }

  /**
   * The outcome of a creation.
   *
   * @param item the item, whether just created or found already present
   * @param created {@code true} when this call created it, which decides {@code 201} versus
   *     {@code 200}
   */
  public record CreateResult(ItemView item, boolean created) {}

  /**
   * Reads one item.
   *
   * @param id the item
   * @return the item as the published view
   * @throws NotFoundException when the tenant has no such live item. An item of another tenant
   *     produces the same failure as one that never existed — telling them apart would let a caller
   *     confirm the existence of a foreign id (REQ-SEC-016).
   */
  @Transactional(readOnly = true)
  public ItemView get(UUID id) {
    UUID tenantId = TenantContext.require();
    return items.findLive(tenantId, id).map(ItemService::toView).orElseThrow(() -> new NotFoundException("item", id));
  }

  /**
   * Changes an item.
   *
   * <p>Stage 0 has no concurrency control on the wire: {@code ETag}/{@code If-Match} is
   * {@code REQ-API-004} and reads stage 1, so there is no header in which a client could tell the
   * server which version it was editing. The {@code version} column is nevertheless maintained —
   * {@code 07 §7.1} requires it on every domain table, and stage 1 turns it into the entity tag
   * without a migration.
   *
   * @param id the item
   * @param command the new values
   * @param actor the authenticated user
   * @return the changed item
   * @throws NotFoundException when the tenant has no such live item
   */
  @Transactional
  public ItemView update(UUID id, UpdateItemCommand command, UUID actor) {
    UUID tenantId = TenantContext.require();
    Item item = items.findLive(tenantId, id).orElseThrow(() -> new NotFoundException("item", id));

    item.update(
        command.name(),
        command.description(),
        command.locationId(),
        command.quantity() != null ? command.quantity() : BigDecimal.ONE,
        command.quantityUnit(),
        actor,
        Instant.now(clock));

    return toView(item);
  }

  /**
   * Deletes an item, leaving a tombstone.
   *
   * <p>Idempotent by design: a client that never saw the first response retries, and the second
   * request must not fail. Deleting an item that is already deleted therefore succeeds quietly.
   *
   * @param id the item
   * @param actor the authenticated user
   * @throws NotFoundException when the tenant never had such an item
   */
  @Transactional
  public void delete(UUID id, UUID actor) {
    UUID tenantId = TenantContext.require();
    Item item = items.findAny(tenantId, id).orElseThrow(() -> new NotFoundException("item", id));
    item.markDeleted(actor, Instant.now(clock));
  }

  /**
   * Converts the aggregate into the type other blocks may hold.
   *
   * @param item the aggregate, managed by the current persistence context
   * @return an immutable view carrying no persistence context
   */
  private static ItemView toView(Item item) {
    return new ItemView(
        item.getId(),
        item.getName(),
        item.getDescription(),
        item.getKind().name(),
        item.getLocationId(),
        item.getQuantity(),
        item.getQuantityUnit(),
        item.getLifecycleState(),
        item.getCreatedAt(),
        item.getUpdatedAt(),
        item.getVersion());
  }

  /**
   * What is needed to create an item.
   *
   * @param id the client's chosen id, or {@code null} to have one generated
   * @param itemTypeVersionId the type version, or {@code null} to use the tenant's built-in type,
   *     which is what every stage-0 client does because there is no type system to choose from
   * @param name the name; must not be blank
   * @param description free text, may be {@code null}
   * @param kind physical or digital
   * @param locationId required for a physical item
   * @param quantity how many; {@code null} means one
   * @param quantityUnit the unit, may be {@code null}
   */
  public record CreateItemCommand(
      UUID id,
      UUID itemTypeVersionId,
      String name,
      String description,
      ItemKind kind,
      UUID locationId,
      BigDecimal quantity,
      String quantityUnit) {}

  /**
   * What may be changed about an item. {@code kind} is absent on purpose — see {@link Item#update}.
   *
   * @param name the new name; must not be blank
   * @param description the new description, may be {@code null}
   * @param locationId the new location; required while the item is physical
   * @param quantity the new quantity; {@code null} means one
   * @param quantityUnit the new unit, may be {@code null}
   */
  public record UpdateItemCommand(
      String name, String description, UUID locationId, BigDecimal quantity, String quantityUnit) {}
}
