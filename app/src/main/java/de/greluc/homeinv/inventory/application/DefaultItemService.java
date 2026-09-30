/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.application;

import de.greluc.homeinv.platform.Page;
import de.greluc.homeinv.catalog.api.CatalogProvisioning;
import de.greluc.homeinv.inventory.api.ItemAlreadyExistsException;
import de.greluc.homeinv.inventory.api.ItemService;
import de.greluc.homeinv.inventory.api.ItemView;
import de.greluc.homeinv.inventory.domain.Item;
import de.greluc.homeinv.inventory.api.ItemKind;
import de.greluc.homeinv.inventory.infrastructure.ItemRepository;
import de.greluc.homeinv.platform.CursorCodec;
import de.greluc.homeinv.platform.NotFoundException;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.platform.Versions;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.time.Instant;
import java.util.Objects;
import de.greluc.homeinv.idempotency.api.RequestKey;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
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

  /** Where a spent {@code Idempotency-Key} is looked up and recorded (REQ-API-005). */
  private final de.greluc.homeinv.idempotency.api.IdempotentRequests requests;

  /** What a key is spent on, so one key cannot create an item and a place. */
  private static final String CREATE_ITEM = "POST /api/v1/items";

  /** What a trash cursor is bound to, so it cannot resume the live listing. */
  private static final String TRASH_CURSOR = "items-trashed";

  /** The cap REQ-NFR-010 puts on every page of every collection. */
  private static final int MAX_PAGE = 200;

  /** Signs and checks the keyset cursors, as every other listing does. */
  private final CursorCodec cursors;
  private final CatalogProvisioning catalog;
  private final Clock clock;

  /**
   * Resolving a type to the version an item is written against, and reading its fields.
   *
   * <p>Separate from {@link de.greluc.homeinv.catalog.api.CatalogProvisioning}, which answers the
   * one question a tenant's creation asks. This is the running system's.
   */
  private final de.greluc.homeinv.catalog.api.TypeRegistry types;

  /** Seals what the type marks sensitive, and keeps what this caller was never shown. */
  private final de.greluc.homeinv.catalog.api.AttributeSealing sealing;

  /** The binding check of REQ-CORE-005, against the document the version generated. */
  private final de.greluc.homeinv.catalog.api.AttributeValidator validator;

  /** Writes the side table, inside the same transaction as the item. */
  private final de.greluc.homeinv.inventory.infrastructure.AttributeProjector projector;

  /** Keeps what every change produced, so an earlier state can be read and put back. */
  private final de.greluc.homeinv.audit.api.RevisionLog revisions;

  /** Turns an item into the snapshot a revision carries, and back. */
  private final tools.jackson.databind.ObjectMapper json;

  /** Tells the blocks that hang things on an item that it is gone for good. */
  private final org.springframework.context.ApplicationEventPublisher events;

  /**
   * What the tenant may hold (REQ-TEN-009).
   *
   * <p>Called before the row is written and never after: 04 §4.3 says the guard runs "before every
   * creating operation … that way abuse limitation is not something to bolt on later". The claim is
   * in this transaction, so an item that fails to be created returns its claim with the rollback.
   */
  private final de.greluc.homeinv.tenancy.api.QuotaGuard quotas;

  /**
   * Removes the sensitive attributes this caller may not read (REQ-TEN-008).
   *
   * <p>On the way out and never on the way in. What is stored is complete — the audit snapshot, the
   * index, a restore — and what a particular person is shown is a projection of it. Redacting on
   * write would make the stored row depend on who happened to save it.
   */
  private final de.greluc.homeinv.catalog.api.AttributeRedaction redaction;

  /**
   * Answers whether an item's place lies in the part of the tree this session is confined to
   * (REQ-TEN-007).
   *
   * <p>Checked on the loaded item, which is 12 §12.5's layer three. An item with no place is not in
   * anybody's garage: a scoped session does not see it, which is what confining somebody to a place
   * has to mean if it is to mean anything.
   */
  private final de.greluc.homeinv.inventory.api.PlaceScope scope;

  /**
   * Whether an item is out on loan (REQ-LIFE-005).
   *
   * <p>Asked before a deletion and nowhere else here. "A lent item is ... not deletable" is
   * REQ-LIFE-005's acceptance criterion, and the reason is that the loan row is the only record of
   * who has the thing: trashing the item would take the question and the answer away together.
   */
  private final de.greluc.homeinv.inventory.api.LoanLog loans;

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
  public CreateResult create(
      CreateItemCommand command, Optional<RequestKey> idempotency, UUID actor) {
    UUID tenantId = TenantContext.require();
    UUID id = command.id() != null ? command.id() : UUID.randomUUID();

    Optional<String> answered =
        idempotency.flatMap(key -> requests.replay(CREATE_ITEM, key.key(), key.requestHash()));
    if (answered.isPresent()) {
      return json.readValue(answered.get(), CreateResult.class);
    }

    Optional<Item> existing = items.findAny(tenantId, id);
    if (existing.isPresent()) {
      Item found = existing.get();
      if (!sameContent(found, command)) {
        throw new ItemAlreadyExistsException(id);
      }
      return remembered(idempotency, new CreateResult(toView(found), false), actor);
    }

    requireInScope(command.locationId());

    quotas.require(de.greluc.homeinv.tenancy.api.QuotaGuard.Quota.ITEM_COUNT, 1);

    UUID typeVersionId =
        command.itemTypeId() != null
            ? types.publishedItemTypeVersion(command.itemTypeId())
            : catalog.builtinItemTypeVersion(tenantId);
    String attributes =
        sealing.sealed(
            typeVersionId,
            id,
            validated(typeVersionId, sealing.merged(typeVersionId, id, command.attributes(), null)));

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
            command.notes(),
            command.minimumStock(),
            command.valuation(),
            command.maintenanceIntervalDays(),
            actor,
            now);

    items.saveAndFlush(item);
    projector.project(id, typeVersionId, attributes);
    revisions.record(
        de.greluc.homeinv.audit.api.RevisionLog.EntityType.ITEM,
        id,
        de.greluc.homeinv.audit.api.RevisionLog.ChangeKind.CREATED,
        snapshot(item),
        actor);
    events.publishEvent(
        new de.greluc.homeinv.inventory.api.ItemCreated(
            tenantId,
            id,
            item.getItemTypeVersionId(),
            item.getName(),
            item.getLocationId()));
    if (item.isBelowMinimum()) {
      events.publishEvent(
          new de.greluc.homeinv.inventory.api.StockBelowMinimum(
              tenantId, id, item.getQuantity(), item.getMinimumStock()));
    }
    log.debug("Item {} created in tenant {}", id, tenantId);
    return remembered(idempotency, new CreateResult(toView(item), true), actor);
  }

  /**
   * Records what a key answered, if a key was sent, and hands the answer back.
   *
   * <p>After the work and before the commit, which is the ordering REQ-API-005 turns on: a record
   * written first could outlive work that failed, and one written after the commit could be lost
   * while the item stayed -- the duplicate this exists to prevent.
   *
   * <p>Also called on the path where the item was already there. That looks redundant and is not:
   * a client whose id-based retry succeeded has spent its key, and a THIRD attempt with the same
   * key and a body it has since edited must be refused rather than quietly answered.
   *
   * @param idempotency the key and hash, if any
   * @param result what to answer
   * @param actor the authenticated user
   * @return the result, unchanged
   */
  private CreateResult remembered(
      Optional<RequestKey> idempotency, CreateResult result, UUID actor) {
    idempotency.ifPresent(
        key ->
            requests.remember(
                CREATE_ITEM,
                key.key(),
                key.requestHash(),
                json.writeValueAsString(result),
                actor));
    return result;
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
    return Objects.equals(item.getName(), command.name())
        && Objects.equals(item.getDescription(), command.description())
        && item.getKind() == command.kind()
        && Objects.equals(item.getLocationId(), command.locationId())
        && item.getQuantity().compareTo(wanted) == 0
        && Objects.equals(item.getQuantityUnit(), command.quantityUnit())
        && Objects.equals(
            item.getNotes(), de.greluc.homeinv.inventory.domain.Notes.sanitise(command.notes()));
  }

  /**
   * Reads one item.
   *
   * @param id the item
   * @return the item as the published view
   * @throws NotFoundException when the tenant has no such live item. An item of another tenant
   *     produces the same failure as one that never existed — telling them apart would let a caller
   *     confirm the existence of a foreign id (REQ-SEC-025).
   */
  @Transactional(readOnly = true)
  @Override
  public ItemView get(UUID id) {
    UUID tenantId = TenantContext.require();
    return items.findLive(tenantId, id).map(this::toView).orElseThrow(() -> new NotFoundException("item", id));
  }

  /**
   * Changes an item.
   *
   * <p>{@code expectedVersion} is what the caller's {@code If-Match} carried, and the check is
   * {@link Versions#requireCurrent}: the {@code version} column 07 §7.1 requires on every domain
   * table is the entity tag (REQ-API-004). Empty skips it, which is what an internal caller with no
   * screen to go stale passes.
   *
   * @param id the item
   * @param command the new values
   * @param expectedVersion the version the caller acted on, or empty
   * @param actor the authenticated user
   * @return the changed item
   * @throws NotFoundException when the tenant has no such live item
   */
  @Transactional
  @Override
  public ItemView update(
      UUID id, UpdateItemCommand command, OptionalLong expectedVersion, UUID actor) {
    UUID tenantId = TenantContext.require();
    Item item = items.findLive(tenantId, id).orElseThrow(() -> new NotFoundException("item", id));
    Versions.requireCurrent("item", id, expectedVersion, item.getVersion());

    String attributes =
        sealing.sealed(
            item.getItemTypeVersionId(),
            id,
            validated(
                item.getItemTypeVersionId(),
                sealing.merged(
                    item.getItemTypeVersionId(), id, command.attributes(), item.getAttributes())));
    boolean wasBelow = item.isBelowMinimum();
    String attributesBefore = item.getAttributes();

    item.update(
        command.name(),
        command.description(),
        command.locationId(),
        command.quantity() != null ? command.quantity() : BigDecimal.ONE,
        command.quantityUnit(),
        attributes,
        command.notes(),
        command.minimumStock(),
        command.valuation(),
        command.maintenanceIntervalDays(),
        actor,
        Instant.now(clock));

    items.flush();
    projector.project(id, item.getItemTypeVersionId(), attributes);
    revisions.record(
        de.greluc.homeinv.audit.api.RevisionLog.EntityType.ITEM,
        id,
        de.greluc.homeinv.audit.api.RevisionLog.ChangeKind.UPDATED,
        snapshot(item),
        actor);
    events.publishEvent(
        new de.greluc.homeinv.inventory.api.ItemUpdated(
            tenantId, id, item.getName(), !attributes.equals(attributesBefore)));
    if (!wasBelow && item.isBelowMinimum()) {
      events.publishEvent(
          new de.greluc.homeinv.inventory.api.StockBelowMinimum(
              tenantId, id, item.getQuantity(), item.getMinimumStock()));
    }
    return toView(item);
  }

  /**
   * Reads many items at once, in the order they were asked for.
   *
   * @param ids the items
   * @return those this tenant can see, in the order given
   */
  @Transactional(readOnly = true)
  @Override
  public List<ItemView> byIds(List<UUID> ids) {
    if (ids == null || ids.isEmpty()) {
      return List.of();
    }
    UUID tenantId = TenantContext.require();
    java.util.Map<UUID, Item> found = new java.util.HashMap<>();
    for (Item item : items.findLiveIn(tenantId, ids)) {
      found.put(item.getId(), item);
    }
    return ids.stream().map(found::get).filter(java.util.Objects::nonNull).map(this::toView).toList();
  }

  /**
   * Puts an item in another place.
   *
   * @param id the item
   * @param locationId where it goes
   * @param expectedVersion the version the caller acted on, or empty
   * @param actor the authenticated user
   * @return the item, in its new place
   * @throws NotFoundException when the tenant has no such live item, or when the place lies outside
   *     the subtree this session is confined to
   */
  @Transactional
  @Override
  public ItemView move(UUID id, UUID locationId, OptionalLong expectedVersion, UUID actor) {
    UUID tenantId = TenantContext.require();
    Item item = items.findLive(tenantId, id).orElseThrow(() -> new NotFoundException("item", id));
    Versions.requireCurrent("item", id, expectedVersion, item.getVersion());
    requireInScope(locationId);

    UUID from = item.getLocationId();
    item.movedTo(locationId, actor, Instant.now(clock));
    items.flush();
    revisions.record(
        de.greluc.homeinv.audit.api.RevisionLog.EntityType.ITEM,
        id,
        de.greluc.homeinv.audit.api.RevisionLog.ChangeKind.UPDATED,
        snapshot(item),
        actor);
    events.publishEvent(
        new de.greluc.homeinv.inventory.api.ItemMoved(tenantId, id, from, locationId));
    log.debug("Item {} moved to location {} in tenant {}", id, locationId, tenantId);
    return toView(item);
  }

  /**
   * Writes an item against another type (REQ-CORE-011).
   *
   * @param id the item
   * @param itemTypeId the type it is to be written against
   * @param expectedVersion the version the caller acted on, or empty
   * @param actor the authenticated user
   * @return the item, on its new type version
   * @throws NotFoundException when the tenant has no such live item, or no such type
   */
  @Transactional
  @Override
  public ItemView changeType(UUID id, UUID itemTypeId, OptionalLong expectedVersion, UUID actor) {
    UUID tenantId = TenantContext.require();
    Item item = items.findLive(tenantId, id).orElseThrow(() -> new NotFoundException("item", id));
    Versions.requireCurrent("item", id, expectedVersion, item.getVersion());

    UUID target = types.publishedItemTypeVersion(itemTypeId);
    if (target.equals(item.getItemTypeVersionId())) {
      return toView(item);
    }

    String attributes =
        sealing.sealed(
            target,
            id,
            validated(
                target,
                sealing.merged(
                    target,
                    id,
                    carriedOver(item.getItemTypeVersionId(), target, item.getAttributes()),
                    item.getAttributes())));

    UUID previous = item.getItemTypeVersionId();
    item.changedType(target, attributes, actor, Instant.now(clock));
    items.flush();
    projector.project(id, target, attributes);
    revisions.record(
        de.greluc.homeinv.audit.api.RevisionLog.EntityType.ITEM,
        id,
        de.greluc.homeinv.audit.api.RevisionLog.ChangeKind.UPDATED,
        snapshot(item),
        actor);
    events.publishEvent(
        new de.greluc.homeinv.inventory.api.ItemTypeChanged(tenantId, id, previous, target));
    log.debug("Item {} written against type version {} in tenant {}", id, target, tenantId);
    return toView(item);
  }

  /**
   * The attributes that survive a change of type.
   *
   * <p>A value is carried over when the new version declares the same key with the same data type,
   * and dropped otherwise (decided with the owner, 2026-09-13). Same key alone is not enough: a
   * {@code quantity} that became a {@code text} would carry a number into a field that reads
   * strings, and the validation would refuse the whole item for a reason nobody could act on.
   *
   * <p>One further case is dropped rather than carried: a field the old version marked
   * {@code sensitive} and the new one does not. What is stored for such a field is ciphertext
   * (ADR-0019), and leaving it in a field that is no longer sealed would write the ciphertext into
   * the database as though it were the value -- unreadable, and indistinguishable from a value.
   *
   * @param fromVersionId the version the item is written against now
   * @param toVersionId the version it is to be written against
   * @param attributes what is stored, sealed values included
   * @return the subset to validate against the new version, as JSON text
   */
  private String carriedOver(UUID fromVersionId, UUID toVersionId, String attributes) {
    if (attributes == null || attributes.isBlank()) {
      return "{}";
    }
    if (!(json.readTree(attributes)
        instanceof tools.jackson.databind.node.ObjectNode stored)) {
      return "{}";
    }
    java.util.Map<String, de.greluc.homeinv.catalog.api.FieldDefinitionView> before =
        fieldsByKey(fromVersionId);
    java.util.Map<String, de.greluc.homeinv.catalog.api.FieldDefinitionView> after =
        fieldsByKey(toVersionId);

    tools.jackson.databind.node.ObjectNode carried = json.createObjectNode();
    stored
        .properties()
        .forEach(
            entry -> {
              de.greluc.homeinv.catalog.api.FieldDefinitionView was = before.get(entry.getKey());
              de.greluc.homeinv.catalog.api.FieldDefinitionView is = after.get(entry.getKey());
              if (was == null || is == null || was.dataType() != is.dataType()) {
                return;
              }
              if (was.sensitive() && !is.sensitive()) {
                return;
              }
              carried.set(entry.getKey(), entry.getValue());
            });
    return json.writeValueAsString(carried);
  }

  /**
   * A version's field definitions, by key.
   *
   * @param typeVersionId the version
   * @return the definitions, keyed by the attribute key they describe
   */
  private java.util.Map<String, de.greluc.homeinv.catalog.api.FieldDefinitionView> fieldsByKey(
      UUID typeVersionId) {
    return types.fields(typeVersionId).stream()
        .collect(
            java.util.stream.Collectors.toMap(
                de.greluc.homeinv.catalog.api.FieldDefinitionView::key, field -> field));
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
  public void delete(UUID id, OptionalLong expectedVersion, UUID actor) {
    UUID tenantId = TenantContext.require();
    Item item = items.findAny(tenantId, id).orElseThrow(() -> new NotFoundException("item", id));
    Versions.requireCurrent("item", id, expectedVersion, item.getVersion());
    if (loans.isLent(id)) {
      throw new de.greluc.homeinv.inventory.api.ItemLentException(
          "This item is lent out. Record its return before deleting it.");
    }
    item.markDeleted(actor, Instant.now(clock));
    items.flush();
    revisions.record(
        de.greluc.homeinv.audit.api.RevisionLog.EntityType.ITEM,
        id,
        de.greluc.homeinv.audit.api.RevisionLog.ChangeKind.TRASHED,
        snapshot(item),
        actor);
    projector.clear(id);
    events.publishEvent(new de.greluc.homeinv.inventory.api.ItemDeleted(tenantId, id));
  }

  @Transactional
  @Override
  public de.greluc.homeinv.inventory.api.ItemView dispose(
      UUID id,
      de.greluc.homeinv.inventory.api.ItemService.Disposal disposal,
      OptionalLong expectedVersion,
      UUID actor) {
    UUID tenantId = TenantContext.require();
    Item item = items.findAny(tenantId, id).orElseThrow(() -> new NotFoundException("item", id));
    Versions.requireCurrent("item", id, expectedVersion, item.getVersion());

    item.dispose(
        disposal.state(),
        disposal.price() == null ? null : disposal.price().amount(),
        disposal.price() == null ? null : disposal.price().currency().getCurrencyCode(),
        disposal.on(),
        disposal.recipient(),
        disposal.note(),
        actor,
        Instant.now(clock));
    items.flush();

    revisions.record(
        de.greluc.homeinv.audit.api.RevisionLog.EntityType.ITEM,
        id,
        de.greluc.homeinv.audit.api.RevisionLog.ChangeKind.UPDATED,
        snapshot(item),
        actor);
    events.publishEvent(
        new de.greluc.homeinv.inventory.api.ItemDisposed(
            tenantId, id, item.getLifecycleState(), disposal.on()));
    log.info("Item {} left the inventory as {}", id, item.getLifecycleState());
    return toView(item);
  }

  @Transactional
  @Override
  public ItemView restore(UUID id, OptionalLong expectedVersion, UUID actor) {
    UUID tenantId = TenantContext.require();
    Item item = items.findAny(tenantId, id).orElseThrow(() -> new NotFoundException("item", id));
    Versions.requireCurrent("item", id, expectedVersion, item.getVersion());
    if (!item.isDeleted()) {
      return toView(item);
    }
    item.restore(actor, Instant.now(clock));
    items.flush();
    projector.project(id, item.getItemTypeVersionId(), item.getAttributes());
    revisions.record(
        de.greluc.homeinv.audit.api.RevisionLog.EntityType.ITEM,
        id,
        de.greluc.homeinv.audit.api.RevisionLog.ChangeKind.RESTORED,
        snapshot(item),
        actor);
    events.publishEvent(new de.greluc.homeinv.inventory.api.ItemRestored(tenantId, id));
    log.debug("Item {} restored in tenant {}", id, tenantId);
    return toView(item);
  }

  @Transactional
  @Override
  public void purge(UUID id, OptionalLong expectedVersion, UUID actor) {
    UUID tenantId = TenantContext.require();
    Item item = items.findAny(tenantId, id).orElseThrow(() -> new NotFoundException("item", id));
    Versions.requireCurrent("item", id, expectedVersion, item.getVersion());
    if (!item.isDeleted()) {
      throw new IllegalStateException(
          "This item is not in the trash. Final removal is the second stage of a deletion, not a "
              + "shortcut past the first (REQ-CORE-009).");
    }

    revisions.record(
        de.greluc.homeinv.audit.api.RevisionLog.EntityType.ITEM,
        id,
        de.greluc.homeinv.audit.api.RevisionLog.ChangeKind.PURGED,
        snapshot(item),
        actor);

    events.publishEvent(new de.greluc.homeinv.inventory.api.ItemPurged(tenantId, id));

    items.delete(item);
    items.flush();
    quotas.release(de.greluc.homeinv.tenancy.api.QuotaGuard.Quota.ITEM_COUNT, 1);
    log.info("Item {} purged in tenant {} by {}", id, tenantId, actor);
  }

  @Transactional(readOnly = true)
  @Override
  public Page<ItemView> trashed(String cursor, int limit) {
    UUID tenantId = TenantContext.require();
    int size = Math.clamp(limit, 1, MAX_PAGE);
    List<Item> rows;
    if (cursor == null || cursor.isBlank()) {
      rows = items.findTrashed(tenantId, PageRequest.of(0, size));
    } else {
      CursorCodec.Position after = cursors.decode(cursor, TRASH_CURSOR);
      rows = items.findTrashedAfter(tenantId, after.createdAt(), after.id(), PageRequest.of(0, size));
    }
    List<ItemView> views = rows.stream().map(this::toView).toList();
    String next = null;
    if (rows.size() == size) {
      Item last = rows.get(rows.size() - 1);
      next = cursors.encode(CursorCodec.Position.of(last.getCreatedAt(), last.getId()), TRASH_CURSOR);
    }
    return Page.of(views, next);
  }

  @Transactional(readOnly = true)
  @Override
  public Page<de.greluc.homeinv.audit.api.RevisionLog.RevisionView> history(
      UUID id, String cursor, int limit) {
    UUID tenantId = TenantContext.require();
    boolean known =
        items.findAny(tenantId, id).isPresent()
            || !revisions
                .history(de.greluc.homeinv.audit.api.RevisionLog.EntityType.ITEM, id, null, 1)
                .data()
                .isEmpty();
    if (!known) {
      throw new NotFoundException("item", id);
    }
    var page =
        revisions.history(
            de.greluc.homeinv.audit.api.RevisionLog.EntityType.ITEM, id, cursor, limit);
    return Page.of(
        page.data().stream().map(revision -> redacted(id, revision)).toList(),
        page.nextCursor());
  }

  /**
   * One revision with the attributes its snapshot carries redacted for this caller.
   *
   * <p>The snapshot is JSON text holding, among other things, the type version the item was written
   * against and its attributes as a nested JSON string. Both are read back out here rather than
   * being stored separately: the record is what it is, and a second copy of the version id beside it
   * would be one more thing that can disagree.
   *
   * @param itemId the item the history belongs to, which each seal is bound to
   * @param revision the stored revision
   * @return the same revision with its snapshot redacted, or unchanged when there was nothing to do
   */
  private de.greluc.homeinv.audit.api.RevisionLog.RevisionView redacted(
      UUID itemId, de.greluc.homeinv.audit.api.RevisionLog.RevisionView revision) {

    if (revision.snapshot() == null || revision.snapshot().isBlank()) {
      return revision;
    }
    Snapshot stored = json.readValue(revision.snapshot(), Snapshot.class);
    String visible =
        redaction.forCaller(stored.itemTypeVersionId(), itemId, stored.attributes());
    if (java.util.Objects.equals(visible, stored.attributes())) {
      return revision;
    }
    Snapshot shown =
        new Snapshot(
            stored.name(),
            stored.description(),
            stored.kind(),
            stored.locationId(),
            stored.quantity(),
            stored.quantityUnit(),
            visible,
            stored.notes(),
            stored.minimumStock(),
            stored.itemTypeVersionId(),
            stored.lifecycleState(),
            stored.valuation(),
            stored.maintenanceIntervalDays());
    return new de.greluc.homeinv.audit.api.RevisionLog.RevisionView(
        revision.revision(),
        revision.kind(),
        json.writeValueAsString(shown),
        revision.changedAt(),
        revision.changedBy());
  }

  @Transactional
  @Override
  public ItemView restoreRevision(
      UUID id, long revision, OptionalLong expectedVersion, UUID actor) {
    UUID tenantId = TenantContext.require();
    Item item = items.findLive(tenantId, id).orElseThrow(() -> new NotFoundException("item", id));
    Versions.requireCurrent("item", id, expectedVersion, item.getVersion());
    var record =
        revisions.revision(de.greluc.homeinv.audit.api.RevisionLog.EntityType.ITEM, id, revision);
    Snapshot earlier = json.readValue(record.snapshot(), Snapshot.class);

    String attributes =
        sealing.sealed(
            item.getItemTypeVersionId(),
            id,
            validated(
                item.getItemTypeVersionId(),
                sealing.merged(
                    item.getItemTypeVersionId(), id, earlier.attributes(), item.getAttributes())));

    item.update(
        earlier.name(),
        earlier.description(),
        earlier.locationId(),
        earlier.quantity() != null ? earlier.quantity() : BigDecimal.ONE,
        earlier.quantityUnit(),
        attributes,
        earlier.notes(),
        earlier.minimumStock(),
        earlier.valuation(),
        earlier.maintenanceIntervalDays(),
        actor,
        Instant.now(clock));
    items.flush();
    projector.project(id, item.getItemTypeVersionId(), attributes);
    revisions.record(
        de.greluc.homeinv.audit.api.RevisionLog.EntityType.ITEM,
        id,
        de.greluc.homeinv.audit.api.RevisionLog.ChangeKind.UPDATED,
        snapshot(item),
        actor);
    log.debug("Item {} restored to revision {} in tenant {}", id, revision, tenantId);
    return toView(item);
  }

  /**
   * The state of an item, as a revision carries it.
   *
   * <p>Written by this block and read by this block. It is deliberately not {@link ItemView}: a view
   * is a published type that may gain and lose fields as the API changes, and a snapshot written
   * last year has to stay readable. What it holds is what a restore puts back, plus the type version
   * so a reader can tell which definitions the attributes were written against.
   *
   * @param item the aggregate
   * @return the snapshot as JSON text
   */
  private String snapshot(Item item) {
    return json.writeValueAsString(
        new Snapshot(
            item.getName(),
            item.getDescription(),
            item.getKind().name(),
            item.getLocationId(),
            item.getQuantity(),
            item.getQuantityUnit(),
            item.getAttributes(),
            item.getNotes(),
            item.getMinimumStock(),
            item.getItemTypeVersionId(),
            item.getLifecycleState().name(),
            item.valuation(),
            item.getMaintenanceIntervalDays()));
  }

  /**
   * What a revision of an item holds.
   *
   * @param name the name at that moment
   * @param description the description
   * @param kind physical or digital, which never changes and is recorded so a snapshot is readable
   *     on its own
   * @param locationId where it was
   * @param quantity how many
   * @param quantityUnit the unit
   * @param attributes the type version's fields, as JSON text
   * @param notes the paragraph a person wrote
   * @param minimumStock the restocking level, or {@code null}
   * @param itemTypeVersionId which definitions those attributes were written against
   * @param lifecycleState the state a person reads
   * @param valuation what it cost, what covered it and what replacing it would cost
   * @param maintenanceIntervalDays how often it needed servicing, or {@code null} (REQ-LIFE-004)
   */
  private record Snapshot(
      String name,
      String description,
      String kind,
      UUID locationId,
      BigDecimal quantity,
      String quantityUnit,
      String attributes,
      String notes,
      BigDecimal minimumStock,
      UUID itemTypeVersionId,
      String lifecycleState,
      de.greluc.homeinv.inventory.api.Valuation valuation,
      Integer maintenanceIntervalDays) {}

  /**
   * Refuses a place outside the part of the tree this session is confined to (REQ-TEN-007).
   *
   * <p>Layer three of 12 §12.5, and what it adds over the policy is an <em>answer</em>. Without it
   * the row-level security check refuses the write with a policy violation, which reaches a client
   * as a {@code 500}; with it the caller is told the place is not there, which is what a place they
   * may not see looks like to them (REQ-SEC-025).
   *
   * @param locationId the place being named, or null
   * @throws NotFoundException when the session is scoped and the place is elsewhere
   */
  private void requireInScope(UUID locationId) {
    UUID confinedTo =
        de.greluc.homeinv.platform.CallerContext.current()
            .map(de.greluc.homeinv.platform.CallerContext.Caller::scopeLocationId)
            .orElse(null);
    if (confinedTo != null && !scope.allows(confinedTo, locationId)) {
      throw new NotFoundException("location", locationId);
    }
  }

  /**
   * Converts the aggregate into the type other blocks may hold.
   *
   * @param item the aggregate, managed by the current persistence context
   * @return an immutable view carrying no persistence context
   */
  private ItemView toView(Item item) {
    return new ItemView(
        item.getId(),
        item.getName(),
        item.getDescription(),
        item.getKind().name(),
        item.getItemTypeVersionId(),
        item.getLocationId(),
        item.getQuantity(),
        item.getQuantityUnit(),
        redaction.forCaller(item.getItemTypeVersionId(), item.getId(), item.getAttributes()),
        item.getNotes(),
        item.getMinimumStock(),
        item.getLifecycleState().name(),
        item.getCreatedAt(),
        item.getUpdatedAt(),
        item.valuation(),
        item.getVersion(),
        item.getMaintenanceIntervalDays());
  }

}
