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
import java.time.LocalDate;
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

  /**
   * A paragraph a person writes about this thing (REQ-CORE-014).
   *
   * <p>Limited Markdown, and the limit is applied on the way in: {@link Notes#sanitise} removes the
   * raw HTML every Markdown implementation would otherwise pass through, so what is stored is what
   * will be shown and no reader has to clean it again.
   */
  @Column(name = "notes")
  private String notes;

  /**
   * The level below which this thing needs restocking, or {@code null} (REQ-CORE-008).
   *
   * <p>An item that carries one IS a consumable. There is no separate flag: a flag and a threshold
   * would be two ways of saying the same thing, and one of them would eventually be wrong.
   */
  @Column(name = "minimum_stock")
  private BigDecimal minimumStock;

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

  /**
   * What it cost, what covers it and what replacing it would cost (REQ-LIFE-001/002/014).
   *
   * <p>Ten columns and no embedded type, because the three figures are independent: a row may
   * carry a purchase price and nothing else, or a replacement value written by a plugin and no
   * purchase at all. An {@code @Embedded} would make them one thing that is present or absent
   * together, which is the one shape they never have.
   *
   * <p>Held as {@link BigDecimal} and a three-letter code rather than as {@code Money}: the value
   * type lives in {@code platform} and knows nothing of JPA, and a converter per figure would be
   * three converters to keep in step. {@link #valuation()} assembles them on the way out, which is
   * the one place that has to be right.
   */
  @Column(name = "purchase_amount")
  private BigDecimal purchaseAmount;

  @Column(name = "purchase_currency")
  private String purchaseCurrency;

  @Column(name = "purchased_on")
  private LocalDate purchasedOn;

  @Column(name = "purchase_source")
  private String purchaseSource;

  @Column(name = "warranty_until")
  private LocalDate warrantyUntil;

  @Column(name = "lifetime_warranty", nullable = false)
  private boolean lifetimeWarranty;

  @Column(name = "replacement_amount")
  private BigDecimal replacementAmount;

  @Column(name = "replacement_currency")
  private String replacementCurrency;

  @Column(name = "replacement_as_of")
  private LocalDate replacementAsOf;

  @Column(name = "replacement_source")
  private String replacementSource;

  @Column(name = "current_amount")
  private BigDecimal currentAmount;

  @Column(name = "current_currency")
  private String currentCurrency;

  @Column(name = "current_as_of")
  private LocalDate currentAsOf;

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
      String notes,
      BigDecimal minimumStock,
      de.greluc.homeinv.inventory.api.Valuation valuation,
      UUID actor,
      Instant now) {
    applyValuation(valuation);
    this.id = id;
    this.tenantId = tenantId;
    this.itemTypeVersionId = itemTypeVersionId;
    this.attributes = attributes == null || attributes.isBlank() ? EMPTY_ATTRIBUTES : attributes;
    this.notes = Notes.sanitise(notes);
    this.minimumStock = minimumStock;
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
   * @param notes a paragraph about this thing; HTML in it is removed here
   * @param minimumStock the level below which it needs restocking, or {@code null}
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
      String notes,
      BigDecimal minimumStock,
      de.greluc.homeinv.inventory.api.Valuation valuation,
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
        notes,
        minimumStock,
        valuation,
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
   * @param notes the new notes; HTML in them is removed here
   * @param minimumStock the new restocking level, or {@code null} to stop tracking one
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
      String notes,
      BigDecimal minimumStock,
      de.greluc.homeinv.inventory.api.Valuation valuation,
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
    this.notes = Notes.sanitise(notes);
    this.minimumStock = minimumStock;
    applyValuation(valuation);
    this.updatedBy = actor;
    this.updatedAt = now;
  }

  /**
   * Puts the item in another place.
   *
   * <p>Only the place, so that a move cannot carry an edit with it. {@link #update} is what changes
   * anything else, and a bulk move (REQ-CORE-011) has nothing else to send.
   *
   * @param locationId the new place; must not be null
   * @param actor the user making the change
   * @param now the instant of the change
   * @throws IllegalArgumentException when the item is digital, which resides nowhere, or when no
   *     place is named
   */
  public void movedTo(UUID locationId, UUID actor, Instant now) {
    if (this.kind != ItemKind.PHYSICAL) {
      throw new IllegalArgumentException("A digital item has no location to move between");
    }
    if (locationId == null) {
      throw new IllegalArgumentException("A physical item resides in exactly one location");
    }
    this.locationId = locationId;
    this.updatedBy = actor;
    this.updatedAt = now;
  }

  /**
   * Writes the item against another type version (REQ-CORE-011).
   *
   * <p>The two move together and cannot be set apart: a type version and the attribute set written
   * against it are one fact, and an item holding the version of one and the values of another would
   * fail validation from then on with nothing to point at. Which values survive the change is the
   * caller's decision and is made before this is called.
   *
   * @param typeVersionId the published version of the new type
   * @param attributes the carried-over attribute set, already validated and sealed by the caller
   * @param actor the user making the change
   * @param now the instant of the change
   * @throws IllegalArgumentException when no version is named
   */
  public void changedType(UUID typeVersionId, String attributes, UUID actor, Instant now) {
    if (typeVersionId == null) {
      throw new IllegalArgumentException("An item is always written against a type version");
    }
    this.itemTypeVersionId = typeVersionId;
    this.attributes = attributes == null || attributes.isBlank() ? EMPTY_ATTRIBUTES : attributes;
    this.updatedBy = actor;
    this.updatedAt = now;
  }

  /**
   * Records what the item cost, what covers it and what replacing it would cost.
   *
   * <p>Whole, never in part: an update sends the three figures it means the item to have, and one
   * left out is one deliberately cleared. That is the same rule the attributes follow, and the
   * alternative — merging — would make "remove the purchase price" unsayable.
   *
   * @param valuation the figures, or {@link de.greluc.homeinv.inventory.api.Valuation#NONE}
   */
  private void applyValuation(de.greluc.homeinv.inventory.api.Valuation valuation) {
    de.greluc.homeinv.inventory.api.Valuation values =
        valuation == null ? de.greluc.homeinv.inventory.api.Valuation.NONE : valuation;

    this.purchaseAmount = values.purchase() == null ? null : values.purchase().amount();
    this.purchaseCurrency = values.purchase() == null ? null : values.purchase().currencyCode();
    this.purchasedOn = values.purchasedOn();
    this.purchaseSource = values.purchaseSource();

    this.warrantyUntil = values.warrantyUntil();
    this.lifetimeWarranty = values.lifetimeWarranty();

    this.replacementAmount = values.replacement() == null ? null : values.replacement().amount();
    this.replacementCurrency =
        values.replacement() == null ? null : values.replacement().currencyCode();
    this.replacementAsOf = values.replacementAsOf();
    this.replacementSource =
        values.replacementSource() == null ? null : values.replacementSource().name();

    this.currentAmount = values.currentValue() == null ? null : values.currentValue().amount();
    this.currentCurrency =
        values.currentValue() == null ? null : values.currentValue().currencyCode();
    this.currentAsOf = values.currentValueAsOf();
  }

  /**
   * The three figures as one value (REQ-LIFE-001/002/014).
   *
   * <p>Assembled here rather than mapped by JPA, which is the one place that has to be right: the
   * columns are ten and the value type is one, and a converter per figure would be three
   * converters to keep in step.
   *
   * @return the valuation, {@link de.greluc.homeinv.inventory.api.Valuation#NONE} when nothing was
   *     ever recorded
   */
  public de.greluc.homeinv.inventory.api.Valuation valuation() {
    return new de.greluc.homeinv.inventory.api.Valuation(
        money(purchaseAmount, purchaseCurrency),
        purchasedOn,
        purchaseSource,
        warrantyUntil,
        lifetimeWarranty,
        money(replacementAmount, replacementCurrency),
        replacementAsOf,
        replacementSource == null
            ? null
            : de.greluc.homeinv.inventory.api.Valuation.Provenance.valueOf(replacementSource),
        money(currentAmount, currentCurrency),
        currentAsOf);
  }

  /**
   * An amount and its code as one value, or nothing when neither is there.
   *
   * @param amount the amount column
   * @param currency the currency column
   * @return the money, or {@code null}
   */
  private static de.greluc.homeinv.platform.Money money(BigDecimal amount, String currency) {
    // A database CHECK refuses one without the other, so a row with exactly one
    // of them cannot exist -- and if one ever did, this would be the place that
    // quietly invented a currency for it.
    return amount == null || currency == null
        ? null
        : new de.greluc.homeinv.platform.Money(amount, java.util.Currency.getInstance(currency));
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

  /**
   * Whether this thing is a consumable that has run low (REQ-CORE-008).
   *
   * @return true when a minimum is tracked and the quantity is under it
   */
  public boolean isBelowMinimum() {
    return minimumStock != null && quantity != null && quantity.compareTo(minimumStock) < 0;
  }
}
