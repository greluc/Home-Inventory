/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.homeinv.catalog.api.TypeAdministration;
import de.greluc.homeinv.catalog.api.TypeKind;
import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.inventory.api.ItemCreated;
import de.greluc.homeinv.inventory.api.ItemDeleted;
import de.greluc.homeinv.inventory.api.ItemKind;
import de.greluc.homeinv.inventory.api.ItemMoved;
import de.greluc.homeinv.inventory.api.ItemRestored;
import de.greluc.homeinv.inventory.api.ItemService;
import de.greluc.homeinv.inventory.api.ItemTypeChanged;
import de.greluc.homeinv.inventory.api.ItemUpdated;
import de.greluc.homeinv.inventory.api.ItemView;
import de.greluc.homeinv.inventory.api.Valuation;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What an item's life publishes (REQ-SRCH-005).
 *
 * <p>Six events rather than one "something changed", decided with the owner on 2026-09-14: a
 * consumer that maintains a count per place should not have to re-read every item that was renamed,
 * and a notification about a move is not a notification about an edit. Nothing consumes them yet —
 * the OpenSearch indexer will — which is exactly why they are tested now: an event nobody listens to
 * is an event nobody notices the absence of.
 *
 * <p>Recorded rather than mocked. A publisher that stood in for the real one would be asserting
 * that the test calls itself.
 */
@DisplayName("An item's life")
@RecordApplicationEvents
class ItemLifecycleEventsIT extends AbstractIntegrationTest {

  @Autowired private ItemService items;
  @Autowired private TypeAdministration types;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private AppUserRepository users;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private ApplicationEvents events;
  @Autowired private de.greluc.homeinv.locations.api.LocationService locations;
  @Autowired private de.greluc.homeinv.catalog.api.LocationCategories categories;

  @Test
  @DisplayName("says it was created, with the type and the place it started in")
  void creation() {
    Tenant tenant = newTenant("item-events-create@example.org");
    UUID type = inTenant(tenant, () -> aType(tenant, "tool"));
    UUID id = inTenant(tenant, () -> anItem(tenant, type, "Hammer").id());

    List<ItemCreated> published = events.stream(ItemCreated.class).toList();
    assertThat(published).hasSize(1);
    assertThat(published.getFirst().itemId()).isEqualTo(id);
    assertThat(published.getFirst().tenantId()).isEqualTo(tenant.tenantId());
    assertThat(published.getFirst().name()).isEqualTo("Hammer");
    assertThat(published.getFirst().itemTypeVersionId()).isNotNull();
  }

  @Test
  @DisplayName("tells an edit that touched the attributes from one that did not")
  void editing() {
    Tenant tenant = newTenant("item-events-edit@example.org");
    UUID type = inTenant(tenant, () -> aTypeWithANote(tenant));
    UUID id = inTenant(tenant, () -> anItem(tenant, type, "Hammer").id());

    // A rename, with the attributes left exactly as they were.
    inTenant(tenant, () -> items.update(id, edit("Sledgehammer", null), OptionalLong.empty(), tenant.userId()));
    // And a change of attributes.
    inTenant(
        tenant,
        () -> items.update(id, edit("Sledgehammer", "{\"note\":\"heavy\"}"), OptionalLong.empty(), tenant.userId()));

    List<ItemUpdated> published = events.stream(ItemUpdated.class).toList();
    assertThat(published).hasSize(2);
    assertThat(published.get(0).name()).isEqualTo("Sledgehammer");
    // The flag is what lets a consumer that only mirrors attributes skip a
    // rename. Getting it the wrong way round would make it skip the wrong half.
    assertThat(published.get(0).attributesChanged()).isFalse();
    assertThat(published.get(1).attributesChanged()).isTrue();
  }

  @Test
  @DisplayName("says where a move came from, which is not recoverable afterwards")
  void moving() {
    Tenant tenant = newTenant("item-events-move@example.org");
    UUID type = inTenant(tenant, () -> aPhysicalType(tenant));
    UUID shed = inTenant(tenant, () -> aPlace(tenant, "Shed"));
    UUID attic = inTenant(tenant, () -> aPlace(tenant, "Attic"));
    UUID id = inTenant(tenant, () -> aPhysicalItem(tenant, type, "Hammer", shed).id());

    inTenant(tenant, () -> items.move(id, attic, OptionalLong.empty(), tenant.userId()));

    List<ItemMoved> published = events.stream(ItemMoved.class).toList();
    assertThat(published).hasSize(1);
    assertThat(published.getFirst().itemId()).isEqualTo(id);
    // Where it came from, which nothing can recover afterwards: a consumer that
    // keeps a count per place has to decrement the shed.
    assertThat(published.getFirst().fromLocationId()).isEqualTo(shed);
    assertThat(published.getFirst().toLocationId()).isEqualTo(attic);
    // A move is not an edit, and says so by not being one.
    assertThat(events.stream(ItemUpdated.class)).isEmpty();
  }

  @Test
  @DisplayName("says which type version an item left and which it joined")
  void changingType() {
    Tenant tenant = newTenant("item-events-type@example.org");
    UUID tool = inTenant(tenant, () -> aType(tenant, "tool"));
    UUID book = inTenant(tenant, () -> aType(tenant, "book"));
    ItemView created = inTenant(tenant, () -> anItem(tenant, tool, "Hammer"));

    inTenant(tenant, () -> items.changeType(created.id(), book, OptionalLong.empty(), tenant.userId()));

    List<ItemTypeChanged> published = events.stream(ItemTypeChanged.class).toList();
    assertThat(published).hasSize(1);
    assertThat(published.getFirst().fromItemTypeVersionId())
        .isNotEqualTo(published.getFirst().toItemTypeVersionId());

    // Asking for the type it already has changes nothing, so it says nothing.
    inTenant(tenant, () -> items.changeType(created.id(), book, OptionalLong.empty(), tenant.userId()));
    assertThat(events.stream(ItemTypeChanged.class)).hasSize(1);
  }

  @Test
  @DisplayName("says both stages of a deletion, and the way back")
  void trashAndRestore() {
    Tenant tenant = newTenant("item-events-trash@example.org");
    UUID type = inTenant(tenant, () -> aType(tenant, "tool"));
    UUID id = inTenant(tenant, () -> anItem(tenant, type, "Hammer").id());

    inTenant(tenant, () -> { items.delete(id, OptionalLong.empty(), tenant.userId()); return null; });
    assertThat(events.stream(ItemDeleted.class)).hasSize(1);

    inTenant(tenant, () -> items.restore(id, OptionalLong.empty(), tenant.userId()));
    assertThat(events.stream(ItemRestored.class)).hasSize(1);

    // Restoring something that is not in the trash is a no-op, and says so by
    // saying nothing: a consumer rebuilding a document on every restore would
    // otherwise rebuild on every idle click.
    inTenant(tenant, () -> items.restore(id, OptionalLong.empty(), tenant.userId()));
    assertThat(events.stream(ItemRestored.class)).hasSize(1);
  }

  // -------------------------------------------------------------------------

  private ItemService.UpdateItemCommand edit(String name, String attributes) {
    return new ItemService.UpdateItemCommand(
        name, null, null, BigDecimal.ONE, null, attributes, null, null, Valuation.NONE);
  }

  private ItemView anItem(Tenant tenant, UUID typeId, String name) {
    return items
        .create(
            new ItemService.CreateItemCommand(
                null, typeId, name, null, ItemKind.DIGITAL, null, BigDecimal.ONE, null, null, null,
                null, Valuation.NONE),
            Optional.empty(),
            tenant.userId())
        .item();
  }

  private UUID aType(Tenant tenant, String key) {
    TypeAdministration.ItemTypeView type =
        types.createItemType(
            new TypeAdministration.CreateItemTypeCommand(key, TypeKind.DIGITAL, null, null),
            tenant.userId());
    types.publish(type.draftVersionId(), tenant.userId());
    return type.id();
  }

  /**
   * A type declaring one field, so that an edit has an attribute to change.
   *
   * @param tenant whose type system
   * @return the published type's id
   */
  private UUID aTypeWithANote(Tenant tenant) {
    TypeAdministration.ItemTypeView type =
        types.createItemType(
            new TypeAdministration.CreateItemTypeCommand("tool", TypeKind.DIGITAL, null, null),
            tenant.userId());
    types.addField(
        type.draftVersionId(),
        new TypeAdministration.FieldCommand(
            "note",
            de.greluc.homeinv.catalog.api.FieldDataType.TEXT,
            java.util.Map.of("en", "Note"),
            java.util.Map.of(),
            false,
            null,
            de.greluc.homeinv.catalog.api.FieldConstraints.NONE,
            null,
            null,
            null,
            0,
            false,
            false,
            false,
            false),
        tenant.userId());
    types.publish(type.draftVersionId(), tenant.userId());
    return type.id();
  }

  /**
   * A physical type, because only a physical item has anywhere to be moved to.
   *
   * @param tenant whose type system
   * @return the published type's id
   */
  private UUID aPhysicalType(Tenant tenant) {
    TypeAdministration.ItemTypeView type =
        types.createItemType(
            new TypeAdministration.CreateItemTypeCommand("crate", TypeKind.PHYSICAL, null, null),
            tenant.userId());
    types.publish(type.draftVersionId(), tenant.userId());
    return type.id();
  }

  /**
   * A place at the root of the tenant's tree, in whichever category was seeded first.
   *
   * @param tenant whose tree
   * @param name what to call it
   * @return the location's id
   */
  private UUID aPlace(Tenant tenant, String name) {
    UUID categoryId = categories.list(null, 50).data().getFirst().id();
    return locations
        .create(
            new de.greluc.homeinv.locations.api.LocationService.CreateLocationCommand(
                null, categoryId, null, name, null),
            Optional.empty(),
            tenant.userId())
        .id();
  }

  private ItemView aPhysicalItem(Tenant tenant, UUID typeId, String name, UUID locationId) {
    return items
        .create(
            new ItemService.CreateItemCommand(
                null, typeId, name, null, ItemKind.PHYSICAL, locationId, BigDecimal.ONE, null, null,
                null, null, Valuation.NONE),
            Optional.empty(),
            tenant.userId())
        .item();
  }

  /**
   * Runs one call inside the tenant, each in the transaction the service opens for itself.
   *
   * <p>No transaction of the test's own, and that order is load-bearing: {@code app.tenant_id} is
   * set when a transaction begins, from the context that is current then. A transaction opened
   * <i>around</i> the context would run with none set, and row-level security would refuse the
   * first insert — which it did, the first time this was written the other way round.
   *
   * <p>It is also the production shape: every service method opens and commits its own, so the
   * events land on the same boundaries here as there.
   *
   * @param tenant whose data
   * @param body what to do
   * @param <T> what it answers
   * @return what the body answered
   */
  private <T> T inTenant(Tenant tenant, Supplier<T> body) {
    return TenantContext.callAs(tenant.tenantId(), body);
  }

  private Tenant newTenant(String email) {
    UUID userId = UUID.randomUUID();
    transactions.executeWithoutResult(
        status ->
            users.save(
                AppUser.create(
                    userId, email, "Test", "en", passwordEncoder.encode("irrelevant"), Instant.now())));
    return new Tenant(userId, provisioning.provision("Tenant of " + email, userId));
  }

  /**
   * The tenant this test writes in.
   *
   * @param userId who acts
   * @param tenantId whose data
   */
  private record Tenant(UUID userId, UUID tenantId) {}
}
