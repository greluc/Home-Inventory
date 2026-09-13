/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.domain;

import de.greluc.homeinv.inventory.api.ItemKind;
import de.greluc.homeinv.platform.JsonbType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Type;

/**
 * An item: something owned, physical or digital, in one place.
 *
 * <p>Stage 0 carries the five fixed fields of {@code REQ-CORE-002} and nothing more. Everything
 * else an item might have is either a type field (stage 1, in {@code attributes}) or a tag — the
 * decision that keeps this table from growing a column per idea.
 *
 * <p>This entity never leaves the {@code inventory} block. Other blocks receive an {@code ItemView}
 * (REQ-NFR-023): an entity handed outward carries its persistence context with it, and a caller
 * holding one can modify the aggregate without going through the use case that guards its
 * invariants.
 *
 * <p>Two columns of {@code inventory.item} are deliberately absent here. {@code search_vector_de}
 * and {@code search_vector_en} are {@code GENERATED ALWAYS} in the database (ADR-0047); mapping
 * them would let Hibernate believe it may write them, and the first update would fail against a
 * generated column. Search reads them through {@code JdbcClient}, where they belong.
 */
@Entity
@Table(schema = "inventory", name = "item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA requires it; nothing else should use it.
public class Item {

  /**
   * The identifier, a UUIDv7.
   *
   * <p>Assigned by the application or supplied by the client, never by the database's default: an
   * offline client must be able to create an item and know its id without asking (ADR-0016,
   * REQ-CORE-001). {@code DEFAULT uuidv7()} stays on the column for anything writing SQL directly,
   * but the aggregate always brings its own.
   */
  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  /** What an item with no attributes carries, which is what the column's default says too. */
  private static final String EMPTY_ATTRIBUTES = "{}";

  /**
   * The tenant this item belongs to.
   *
   * <p>Set once at creation from the authenticated context and never updated — an item does not
   * move between tenants, and a setter would be the mechanism by which one could. The column also
   * carries the row-level security policy, so a wrong value here is invisible rather than
   * dangerous.
   */
  @Column(name = "tenant_id", nullable = false, updatable = false)
  private UUID tenantId;

  /** The version of the type this item was created against ({@code REQ-CORE-002}). */
  @Column(name = "item_type_version_id", nullable = false)
  private UUID itemTypeVersionId;

  /** The name, which is the one field a person always fills in. Never blank. */
  @Column(name = "name", nullable = false)
  private String name;

  /** Free text. Indexed for full-text search together with the name (REQ-SRCH-001). */
  @Column(name = "description")
  private String description;

  /**
   * The fields the type version declares, as JSON text (REQ-CORE-005, ADR-0004).
   *
   * <p>The source of truth for every attribute. {@code item_attr_index} mirrors the handful marked
   * searchable, sortable or facetable and is derived from this in the same transaction; where the
   * two ever disagree, this one is right.
   *
   * <p>Text rather than a parsed structure: this block does not interpret it. What a key means is
   * the catalog's business, validation happens before the value arrives here, and a column mapped
   * to a structure would invite this block to reason about fields it does not own.
   */
  @Column(name = "attributes", nullable = false)
  @Type(JsonbType.class)
  private String attributes;

  /** Whether the item exists in the physical world, which decides whether it needs a location. */
  @Enumerated(EnumType.STRING)
  @Column(name = "kind", nullable = false)
  private ItemKind kind;

  /**
   * Where the item is, for a physical item.
   *
   * <p>Null only for a digital item or a deleted one; the database carries the same rule as a check
   * constraint, so the invariant holds even for a write that does not come through this class.
   */
  @Column(name = "location_id")
  private UUID locationId;

  /** How many. Never negative; one by default, because most things are counted once. */
  @Column(name = "quantity", nullable = false)
  private BigDecimal quantity;

  /** The unit the quantity is counted in, or null for bare pieces. */
  @Column(name = "quantity_unit")
  private String quantityUnit;

  /** Where the item is in its life. Stage 0 writes {@code ACTIVE} only. */
  @Column(name = "lifecycle_state", nullable = false)
  private String lifecycleState;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "created_by", updatable = false)
  private UUID createdBy;

  @Column(name = "updated_by")
  private UUID updatedBy;

  /**
   * When the item was deleted, or null while it exists.
   *
   * <p>A domain delete sets this and nothing removes the row (07 §7.1, rule 5): reconciliation needs
   * to learn that something went away, and a missing row says nothing.
   */
  @Column(name = "deleted_at")
  private Instant deletedAt;

  /** Optimistic lock. Two people editing the same item is the ordinary case, not the exception. */
  @Version
  @Column(name = "version", nullable = false)
  private long version;

  /**
   * Creates an item.
   *
   * <p>Private, so the only way in is {@link #create}. A constructor that Lombok generated would be
   * called with eleven arguments in an order nobody can remember, and the invariant below would
   * live wherever the caller happened to check it.
   */
  private Item(
      UUID id,
      UUID tenantId,
      UUID itemTypeVersionId,
      String name,
      String description,
      ItemKind kind,
      UUID locationId,
      BigDecimal quantity,
      String quantityUnit,
      String attributes,
      UUID actor,
      Instant now) {
    this.id = id;
    this.tenantId = tenantId;
    this.itemTypeVersionId = itemTypeVersionId;
    this.attributes = attributes == null || attributes.isBlank() ? EMPTY_ATTRIBUTES : attributes;
    this.name = name;
    this.description = description;
    this.kind = kind;
    this.locationId = locationId;
    this.quantity = quantity;
    this.quantityUnit = quantityUnit;
    this.lifecycleState = "ACTIVE";
    this.createdAt = now;
    this.updatedAt = now;
    this.createdBy = actor;
    this.updatedBy = actor;
  }

  /**
   * Creates an item, enforcing the one invariant the type system cannot express.
   *
   * @param id the identifier, supplied by the client or generated by the caller
   * @param tenantId the owning tenant, from the authenticated context
   * @param itemTypeVersionId the type version this item is created against
   * @param name the name; must not be blank
   * @param description free text, may be null
   * @param kind physical or digital
   * @param locationId where it is; required for a physical item, ignored for a digital one
   * @param quantity how many; must not be negative
   * @param quantityUnit the unit, may be null
   * @param attributes the type version's fields as JSON text, already validated by the caller;
   *     {@code null} means an empty set
   * @param actor the user creating it, recorded in the audit columns
   * @param now the creation instant, passed in so tests need no clock trickery
   * @return the new item, not yet persisted
   * @throws IllegalArgumentException when the name is blank, the quantity is negative, or a
   *     physical item has no location. These are programming errors by the time they reach here —
   *     the REST layer rejects the same cases with a {@code 422} and a field path, so a caller
   *     seeing this exception has bypassed validation rather than received bad input.
   */
  public static Item create(
      UUID id,
      UUID tenantId,
      UUID itemTypeVersionId,
      String name,
      String description,
      ItemKind kind,
      UUID locationId,
      BigDecimal quantity,
      String quantityUnit,
      String attributes,
      UUID actor,
      Instant now) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("An item needs a name");
    }
    if (quantity == null || quantity.signum() < 0) {
      throw new IllegalArgumentException("Quantity must not be negative");
    }
    if (kind == ItemKind.PHYSICAL && locationId == null) {
      throw new IllegalArgumentException("A physical item resides in exactly one location");
    }
    return new Item(
        id,
        tenantId,
        itemTypeVersionId,
        name,
        description,
        kind,
        kind == ItemKind.PHYSICAL ? locationId : null,
        quantity,
        quantityUnit,
        attributes,
        actor,
        now);
  }

  /**
   * Applies an edit to the mutable fields.
   *
   * <p>{@code kind} is not among them: a physical item does not become a digital one, and allowing
   * the change would mean a location that must be dropped and an invariant checked in two
   * directions. Deleting and recreating says the same thing honestly.
   *
   * @param name the new name; must not be blank
   * @param description the new description, may be null
   * @param locationId the new location; required while the item is physical
   * @param quantity the new quantity; must not be negative
   * @param quantityUnit the new unit, may be null
   * @param attributes the new attribute set as JSON text, already validated by the caller
   * @param actor the user making the change
   * @param now the instant of the change
   * @throws IllegalArgumentException under the same conditions as {@link #create}
   */
  public void update(
      String name,
      String description,
      UUID locationId,
      BigDecimal quantity,
      String quantityUnit,
      String attributes,
      UUID actor,
      Instant now) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("An item needs a name");
    }
    if (quantity == null || quantity.signum() < 0) {
      throw new IllegalArgumentException("Quantity must not be negative");
    }
    if (this.kind == ItemKind.PHYSICAL && locationId == null) {
      throw new IllegalArgumentException("A physical item resides in exactly one location");
    }
    this.name = name;
    this.description = description;
    this.locationId = this.kind == ItemKind.PHYSICAL ? locationId : null;
    this.quantity = quantity;
    this.quantityUnit = quantityUnit;
    this.attributes = attributes == null || attributes.isBlank() ? EMPTY_ATTRIBUTES : attributes;
    this.updatedBy = actor;
    this.updatedAt = now;
  }

  /**
   * Brings the item back out of the trash (REQ-CORE-009).
   *
   * <p>Idempotent, like the deletion it undoes: restoring an item that is not in the trash changes
   * nothing and raises nothing.
   *
   * @param actor the user restoring it
   * @param now the instant of the restoration
   * @throws IllegalStateException when the item is physical and the place it was in has since been
   *     removed, because a physical item resides in exactly one location (REQ-CORE-003)
   */
  public void restore(UUID actor, Instant now) {
    if (this.deletedAt == null) {
      return;
    }
    if (this.kind == ItemKind.PHYSICAL && this.locationId == null) {
      throw new IllegalStateException(
          "This item cannot be restored: the place it was in no longer exists, and a physical item "
              + "resides in exactly one location.");
    }
    this.deletedAt = null;
    this.lifecycleState = "ACTIVE";
    this.updatedBy = actor;
    this.updatedAt = now;
  }

  /**
   * Marks the item deleted, leaving the row in place as a tombstone.
   *
   * <p>Idempotent: deleting an already deleted item changes nothing and raises nothing, because a
   * client retrying a request it never saw the answer to must not receive an error for succeeding
   * twice.
   *
   * @param actor the user deleting it
   * @param now the instant of the deletion
   */
  public void markDeleted(UUID actor, Instant now) {
    if (this.deletedAt != null) {
      return;
    }
    this.deletedAt = now;
    // The state a person reads, beside the timestamp the queries filter on. Both
    // move together, so a listing that shows the state and a query that hides the
    // row can never disagree (REQ-CORE-009).
    this.lifecycleState = "TRASHED";
    this.updatedBy = actor;
    this.updatedAt = now;
  }

  /**
   * Whether the item is deleted.
   *
   * @return {@code true} once {@link #markDeleted} has been applied
   */
  public boolean isDeleted() {
    return deletedAt != null;
  }
}
