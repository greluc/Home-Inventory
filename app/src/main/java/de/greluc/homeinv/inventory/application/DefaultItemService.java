/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.application;

import de.greluc.homeinv.catalog.api.CatalogProvisioning;
import de.greluc.homeinv.inventory.api.ItemAlreadyExistsException;
import de.greluc.homeinv.inventory.api.ItemService;
import de.greluc.homeinv.inventory.api.ItemView;
import de.greluc.homeinv.inventory.domain.Item;
import de.greluc.homeinv.inventory.api.ItemKind;
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
 * <p>The interface is {@link ItemService} in the published package; this is the implementation.
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
public class DefaultItemService implements ItemService {

  private final ItemRepository items;
  private final CatalogProvisioning catalog;
  private final Clock clock;

  /**
   * Resolving a type to the version an item is written against, and reading its fields.
   *
   * <p>Separate from {@link de.greluc.homeinv.catalog.api.CatalogProvisioning}, which answers the
   * one question a tenant's creation asks. This is the running system's.
   */
  private final de.greluc.homeinv.catalog.api.TypeRegistry types;

  /** The binding check of REQ-CORE-005, against the document the version generated. */
  private final de.greluc.homeinv.catalog.api.AttributeValidator validator;

  /** Writes the side table, inside the same transaction as the item. */
  private final de.greluc.homeinv.inventory.infrastructure.AttributeProjector projector;

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
  @Override
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

    // A client names the TYPE; the version is resolved here, once, and stored.
    // The item then keeps the shape the type had at this moment however often the
    // type moves on afterwards (REQ-CORE-025). A client that names no type gets
    // the tenant's built-in one, which is what a stage-0 client always did and
    // what a quick capture still does.
    UUID typeVersionId =
        command.itemTypeId() != null
            ? types.publishedItemTypeVersion(command.itemTypeId())
            : catalog.builtinItemTypeVersion(tenantId);
    String attributes = validated(typeVersionId, command.attributes());

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
            attributes,
            actor,
            now);

    // saveAndFlush, not save: the projection below is plain SQL against a table
    // whose foreign key points at this row, and JPA would otherwise hold the
    // insert until the transaction commits — which is after the projection. The
    // database said so plainly: "Key is not present in table item".
    items.saveAndFlush(item);
    // In the same transaction as the write, which is what makes an attribute
    // filter transactionally exact (REQ-CORE-013).
    projector.project(id, typeVersionId, attributes);
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
  /**
   * Checks an attribute set and hands back what should be stored.
   *
   * @param typeVersionId the version the item is written against
   * @param attributes the set as JSON text, possibly {@code null}
   * @return the set to store, {@code {}} where the caller sent nothing
   * @throws de.greluc.homeinv.catalog.api.InvalidAttributesException when the set does not match
   *     the version, carrying one violation per offending value
   */
  private String validated(UUID typeVersionId, String attributes) {
    String candidate = attributes == null || attributes.isBlank() ? "{}" : attributes;
    var result = validator.validate(typeVersionId, candidate);
    if (!result.valid()) {
      throw new de.greluc.homeinv.catalog.api.InvalidAttributesException(result.violations());
    }
    return candidate;
  }

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
   * Reads one item.
   *
   * @param id the item
   * @return the item as the published view
   * @throws NotFoundException when the tenant has no such live item. An item of another tenant
   *     produces the same failure as one that never existed — telling them apart would let a caller
   *     confirm the existence of a foreign id (REQ-SEC-016).
   */
  @Transactional(readOnly = true)
  @Override
  public ItemView get(UUID id) {
    UUID tenantId = TenantContext.require();
    return items.findLive(tenantId, id).map(DefaultItemService::toView).orElseThrow(() -> new NotFoundException("item", id));
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
  @Override
  public ItemView update(UUID id, UpdateItemCommand command, UUID actor) {
    UUID tenantId = TenantContext.require();
    Item item = items.findLive(tenantId, id).orElseThrow(() -> new NotFoundException("item", id));

    // Against the version the item was written against, not against whatever the
    // type says today: an edit to an old item must not start failing because the
    // type moved on (REQ-CORE-025).
    String attributes = validated(item.getItemTypeVersionId(), command.attributes());

    item.update(
        command.name(),
        command.description(),
        command.locationId(),
        command.quantity() != null ? command.quantity() : BigDecimal.ONE,
        command.quantityUnit(),
        attributes,
        actor,
        Instant.now(clock));

    // The dirty aggregate reaches the database here rather than at commit, for
    // the same reason the creation flushes: the projection is SQL and cannot see
    // a change that is still in the persistence context.
    items.flush();
    projector.project(id, item.getItemTypeVersionId(), attributes);
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
  @Override
  public void delete(UUID id, UUID actor) {
    UUID tenantId = TenantContext.require();
    Item item = items.findAny(tenantId, id).orElseThrow(() -> new NotFoundException("item", id));
    item.markDeleted(actor, Instant.now(clock));
    items.flush();
    // The projection goes with it, in the same transaction: a trashed item that
    // kept answering attribute filters would be a deleted thing that is still
    // findable, for the whole retention period (07 §7.3, REQ-CORE-013).
    projector.clear(id);
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
        item.getAttributes(),
        item.getLifecycleState(),
        item.getCreatedAt(),
        item.getUpdatedAt(),
        item.getVersion());
  }

}
