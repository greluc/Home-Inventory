/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.application;

import de.greluc.homeinv.inventory.api.ItemView;
import de.greluc.homeinv.inventory.domain.Item;
import de.greluc.homeinv.inventory.domain.ItemKind;
import de.greluc.homeinv.inventory.infrastructure.ItemRepository;
import de.greluc.homeinv.platform.NotFoundException;
import de.greluc.homeinv.platform.TenantContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
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
  private final Clock clock;

  /**
   * Creates an item.
   *
   * <p>The id may come from the client, because an offline client creates items without asking
   * (ADR-0016). That makes a collision a case the API must answer rather than a constraint
   * violation surfacing as a {@code 500}, so it is checked before the insert. The check and the
   * insert are in one transaction; a concurrent creator loses on the unique constraint, which is
   * the correct outcome and still not a {@code 500} because the id is the client's own.
   *
   * @param command what to create
   * @param actor the authenticated user, recorded in the audit columns
   * @return the created item as the published view
   * @throws ItemAlreadyExistsException when the tenant already has an item with that id
   * @throws IllegalArgumentException when an invariant of {@link Item#create} is violated
   */
  @Transactional
  public ItemView create(CreateItemCommand command, UUID actor) {
    UUID tenantId = TenantContext.require();
    UUID id = command.id() != null ? command.id() : UUID.randomUUID();

    if (items.existsForTenant(tenantId, id)) {
      throw new ItemAlreadyExistsException(id);
    }

    Instant now = Instant.now(clock);
    Item item =
        Item.create(
            id,
            tenantId,
            command.itemTypeVersionId(),
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
    return toView(item);
  }

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
   * @param id the item
   * @param command the new values
   * @param expectedVersion the version the client last saw, echoed back so a concurrent edit is
   *     detected instead of silently overwritten
   * @param actor the authenticated user
   * @return the changed item
   * @throws NotFoundException when the tenant has no such live item
   * @throws StaleItemException when the item changed since {@code expectedVersion}
   */
  @Transactional
  public ItemView update(UUID id, UpdateItemCommand command, long expectedVersion, UUID actor) {
    UUID tenantId = TenantContext.require();
    Item item = items.findLive(tenantId, id).orElseThrow(() -> new NotFoundException("item", id));

    // Checked here rather than left to Hibernate's @Version so the caller gets a
    // named failure with both versions in it. An OptimisticLockException surfaces
    // as a 500 unless something maps it, and what it means to the user — "somebody
    // else edited this" — is lost by then.
    if (item.getVersion() != expectedVersion) {
      throw new StaleItemException(id, expectedVersion, item.getVersion());
    }

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
   * @param itemTypeVersionId the type version; at stage 0 the tenant's built-in type
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
