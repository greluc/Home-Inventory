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
import de.greluc.homeinv.platform.CursorCodec;
import de.greluc.homeinv.platform.NotFoundException;
import de.greluc.homeinv.platform.TenantContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
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

    // Before anything is written, and after the idempotent short circuit above:
    // a repeat of a creation that already happened must not claim a second item.
    quotas.require(de.greluc.homeinv.tenancy.api.QuotaGuard.Quota.ITEM_COUNT, 1);

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
            command.notes(),
            command.minimumStock(),
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
    revisions.record(
        de.greluc.homeinv.audit.api.RevisionLog.EntityType.ITEM,
        id,
        de.greluc.homeinv.audit.api.RevisionLog.ChangeKind.CREATED,
        snapshot(item),
        actor);
    // A consumable that starts below its own minimum is already low; nothing
    // "fell", but the thing a reminder is for is true from the first moment.
    if (item.isBelowMinimum()) {
      events.publishEvent(
          new de.greluc.homeinv.inventory.api.StockBelowMinimum(
              tenantId, id, item.getQuantity(), item.getMinimumStock()));
    }
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
        && Objects.equals(item.getQuantityUnit(), command.quantityUnit())
        // The notes as they would be STORED, not as they were sent: a retry that
        // sends `<b>x</b>` where `x` is stored is the same request, and comparing
        // the raw text would make every such retry a conflict.
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
   *     confirm the existence of a foreign id (REQ-SEC-016).
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
    // Read before the change, so the event below reports a CROSSING rather than a
    // state: an edit to something already known to be low is not news.
    boolean wasBelow = item.isBelowMinimum();

    item.update(
        command.name(),
        command.description(),
        command.locationId(),
        command.quantity() != null ? command.quantity() : BigDecimal.ONE,
        command.quantityUnit(),
        attributes,
        command.notes(),
        command.minimumStock(),
        actor,
        Instant.now(clock));

    // The dirty aggregate reaches the database here rather than at commit, for
    // the same reason the creation flushes: the projection is SQL and cannot see
    // a change that is still in the persistence context.
    items.flush();
    projector.project(id, item.getItemTypeVersionId(), attributes);
    revisions.record(
        de.greluc.homeinv.audit.api.RevisionLog.EntityType.ITEM,
        id,
        de.greluc.homeinv.audit.api.RevisionLog.ChangeKind.UPDATED,
        snapshot(item),
        actor);
    if (!wasBelow && item.isBelowMinimum()) {
      events.publishEvent(
          new de.greluc.homeinv.inventory.api.StockBelowMinimum(
              tenantId, id, item.getQuantity(), item.getMinimumStock()));
    }
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
    revisions.record(
        de.greluc.homeinv.audit.api.RevisionLog.EntityType.ITEM,
        id,
        de.greluc.homeinv.audit.api.RevisionLog.ChangeKind.TRASHED,
        snapshot(item),
        actor);
    // The projection goes with it, in the same transaction: a trashed item that
    // kept answering attribute filters would be a deleted thing that is still
    // findable, for the whole retention period (07 §7.3, REQ-CORE-013).
    projector.clear(id);
  }

  @Transactional
  @Override
  public ItemView restore(UUID id, UUID actor) {
    UUID tenantId = TenantContext.require();
    Item item = items.findAny(tenantId, id).orElseThrow(() -> new NotFoundException("item", id));
    if (!item.isDeleted()) {
      return toView(item);
    }
    item.restore(actor, Instant.now(clock));
    items.flush();
    // The projection comes back with it: trashing removed the item's rows so it
    // would stop answering attribute filters, and restoring has to undo exactly
    // that (07 §7.3).
    projector.project(id, item.getItemTypeVersionId(), item.getAttributes());
    revisions.record(
        de.greluc.homeinv.audit.api.RevisionLog.EntityType.ITEM,
        id,
        de.greluc.homeinv.audit.api.RevisionLog.ChangeKind.RESTORED,
        snapshot(item),
        actor);
    log.debug("Item {} restored in tenant {}", id, tenantId);
    return toView(item);
  }

  @Transactional
  @Override
  public void purge(UUID id, UUID actor) {
    UUID tenantId = TenantContext.require();
    Item item = items.findAny(tenantId, id).orElseThrow(() -> new NotFoundException("item", id));
    if (!item.isDeleted()) {
      throw new IllegalStateException(
          "This item is not in the trash. Final removal is the second stage of a deletion, not a "
              + "shortcut past the first (REQ-CORE-009).");
    }

    // The last revision is written BEFORE the row goes, and says what was
    // destroyed. Afterwards there is nothing left to take a snapshot of.
    revisions.record(
        de.greluc.homeinv.audit.api.RevisionLog.EntityType.ITEM,
        id,
        de.greluc.homeinv.audit.api.RevisionLog.ChangeKind.PURGED,
        snapshot(item),
        actor);

    // Inside this transaction: `media.attachment` is polymorphic and can have no
    // foreign key into the item (07 §7.8), so nothing in the database would
    // remove it and a purge would leave an attachment pointing at an id nothing
    // answers for.
    events.publishEvent(new de.greluc.homeinv.inventory.api.ItemPurged(tenantId, id));

    items.delete(item);
    items.flush();
    // Given back here and not when the item was trashed: a trashed item is still
    // a row and still carries its attachments, and this is the operation that
    // actually gives the space back (REQ-CORE-009).
    quotas.release(de.greluc.homeinv.tenancy.api.QuotaGuard.Quota.ITEM_COUNT, 1);
    log.info("Item {} purged in tenant {} by {}", id, tenantId, actor);
  }

  @Transactional(readOnly = true)
  @Override
  public ItemPage trashed(String cursor, int limit) {
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
      next = cursors.encode(new CursorCodec.Position(last.getCreatedAt(), last.getId()), TRASH_CURSOR);
    }
    return new ItemPage(views, next);
  }

  @Transactional(readOnly = true)
  @Override
  public de.greluc.homeinv.audit.api.RevisionLog.RevisionPage history(
      UUID id, String cursor, int limit) {
    UUID tenantId = TenantContext.require();
    // Either the item is still here or its history is: a purge removes the row
    // and deliberately keeps the record (REQ-CORE-009), so a check for the row
    // alone would make the last thing a person can learn about a removed item
    // unreachable. Both halves are tenant-scoped, so neither says anything about
    // a foreign id (REQ-SEC-016) — and an id this tenant never had is a 404
    // rather than an empty page.
    boolean known =
        items.findAny(tenantId, id).isPresent()
            || !revisions
                .history(de.greluc.homeinv.audit.api.RevisionLog.EntityType.ITEM, id, null, 1)
                .items()
                .isEmpty();
    if (!known) {
      throw new NotFoundException("item", id);
    }
    var page =
        revisions.history(
            de.greluc.homeinv.audit.api.RevisionLog.EntityType.ITEM, id, cursor, limit);
    // The snapshots are stored whole — a restore has to put back the real value —
    // so the redaction happens here, on the way out. Without it the history would
    // be the way round REQ-TEN-008: a purchase price nobody may read today, read
    // out of yesterday.
    return new de.greluc.homeinv.audit.api.RevisionLog.RevisionPage(
        page.items().stream().map(this::redacted).toList(), page.nextCursor());
  }

  /**
   * One revision with the attributes its snapshot carries redacted for this caller.
   *
   * <p>The snapshot is JSON text holding, among other things, the type version the item was written
   * against and its attributes as a nested JSON string. Both are read back out here rather than
   * being stored separately: the record is what it is, and a second copy of the version id beside it
   * would be one more thing that can disagree.
   *
   * @param revision the stored revision
   * @return the same revision with its snapshot redacted, or unchanged when there was nothing to do
   */
  private de.greluc.homeinv.audit.api.RevisionLog.RevisionView redacted(
      de.greluc.homeinv.audit.api.RevisionLog.RevisionView revision) {

    if (revision.snapshot() == null || revision.snapshot().isBlank()) {
      return revision;
    }
    Snapshot stored = json.readValue(revision.snapshot(), Snapshot.class);
    String visible = redaction.forCaller(stored.itemTypeVersionId(), stored.attributes());
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
            stored.lifecycleState());
    return new de.greluc.homeinv.audit.api.RevisionLog.RevisionView(
        revision.revision(),
        revision.kind(),
        json.writeValueAsString(shown),
        revision.changedAt(),
        revision.changedBy());
  }

  @Transactional
  @Override
  public ItemView restoreRevision(UUID id, long revision, UUID actor) {
    UUID tenantId = TenantContext.require();
    Item item = items.findLive(tenantId, id).orElseThrow(() -> new NotFoundException("item", id));
    var record =
        revisions.revision(de.greluc.homeinv.audit.api.RevisionLog.EntityType.ITEM, id, revision);
    Snapshot earlier = json.readValue(record.snapshot(), Snapshot.class);

    // Against the item's own type version, not against the one the snapshot was
    // written with: an item does not travel between versions, and a set that was
    // valid then may not be now — which is a refusal a person can act on rather
    // than a write that quietly stores something the type forbids.
    String attributes = validated(item.getItemTypeVersionId(), earlier.attributes());

    item.update(
        earlier.name(),
        earlier.description(),
        earlier.locationId(),
        earlier.quantity() != null ? earlier.quantity() : BigDecimal.ONE,
        earlier.quantityUnit(),
        attributes,
        earlier.notes(),
        earlier.minimumStock(),
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
            item.getLifecycleState()));
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
      String lifecycleState) {}

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
        item.getLocationId(),
        item.getQuantity(),
        item.getQuantityUnit(),
        redaction.forCaller(item.getItemTypeVersionId(), item.getAttributes()),
        item.getNotes(),
        item.getMinimumStock(),
        item.getLifecycleState(),
        item.getCreatedAt(),
        item.getUpdatedAt(),
        item.getVersion());
  }

}
