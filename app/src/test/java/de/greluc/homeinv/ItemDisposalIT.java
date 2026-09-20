/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.inventory.api.ItemKind;
import de.greluc.homeinv.inventory.api.ItemService;
import de.greluc.homeinv.inventory.api.ItemState;
import de.greluc.homeinv.inventory.api.ItemStateException;
import de.greluc.homeinv.inventory.api.ItemView;
import de.greluc.homeinv.inventory.api.LoanLog;
import de.greluc.homeinv.platform.Money;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Where an item is in its life (04 §4.4, REQ-LIFE-005, REQ-LIFE-007).
 *
 * <p>The state is <b>one</b> value, because 11 §11.3 resolves a sync conflict by ranking these
 * against each other. So the tests here are mostly about the value moving when it should and
 * refusing when it should not — and about one property that is easy to lose: a sold item stays
 * readable. An inventory answers "what did we have and what became of it", and an item that
 * vanished when it was sold would answer half of that.
 */
@DisplayName("An item's life")
class ItemDisposalIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple-42";

  @Autowired private ItemService items;
  @Autowired private LoanLog loans;
  @Autowired private de.greluc.homeinv.locations.api.LocationService locations;
  @Autowired private AppUserRepository users;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private org.springframework.jdbc.core.simple.JdbcClient jdbc;

  @Test
  @DisplayName("starts active, and lending moves it to LENT and back")
  void lendingMovesTheState() {
    Tenant tenant = newTenant("state-lend@example.org");
    UUID itemId = anItem(tenant, "A drill");

    assertThat(stateOf(tenant, itemId)).isEqualTo(ItemState.ACTIVE.name());

    UUID loanId =
        inOwn(
                tenant,
                () ->
                    loans.lend(
                        itemId,
                        new LoanLog.NewLoan(null, "A neighbour", LocalDate.parse("2026-09-01"), null, null),
                        tenant.userId()))
            .id();
    // The state and the loan row move in one transaction, so "is it lent" has one
    // answer however it is asked.
    assertThat(stateOf(tenant, itemId)).isEqualTo(ItemState.LENT.name());

    inOwn(tenant, () -> loans.returnItem(itemId, loanId, LocalDate.parse("2026-09-05"), tenant.userId()));
    assertThat(stateOf(tenant, itemId)).isEqualTo(ItemState.ACTIVE.name());
  }

  @Test
  @DisplayName("records a sale with its price, date and buyer, and keeps the item readable")
  void recordsASale() {
    Tenant tenant = newTenant("state-sold@example.org");
    UUID itemId = anItem(tenant, "A road bike");

    ItemView sold =
        inOwn(
            tenant,
            () ->
                items.dispose(
                    itemId,
                    new ItemService.Disposal(
                        ItemState.SOLD,
                        Money.of("450.00", "EUR"),
                        LocalDate.parse("2026-09-10"),
                        "A colleague",
                        "Collected in person"),
                    OptionalLong.empty(),
                    tenant.userId()));

    assertThat(sold.lifecycleState()).isEqualTo(ItemState.SOLD.name());

    // STILL THERE. This is the property worth the test: a disposal is not a
    // deletion, and the record of what became of a thing is the point of keeping
    // it (REQ-LIFE-007).
    ItemView readBack = inOwn(tenant, () -> items.get(itemId));
    assertThat(readBack.lifecycleState()).isEqualTo(ItemState.SOLD.name());
    assertThat(readBack.name()).isEqualTo("A road bike");
  }

  @Test
  @DisplayName("records a giveaway with no price, which is not the same as a price of zero")
  void recordsAGiveaway() {
    Tenant tenant = newTenant("state-given@example.org");
    UUID itemId = anItem(tenant, "An old printer");

    inOwn(
        tenant,
        () ->
            items.dispose(
                itemId,
                new ItemService.Disposal(
                    ItemState.DISPOSED, null, LocalDate.parse("2026-09-11"), "Recycling centre", null),
                OptionalLong.empty(),
                tenant.userId()));

    assertThat(stateOf(tenant, itemId)).isEqualTo(ItemState.DISPOSED.name());
    // Absent, not 0.00: "nothing was paid" and "it fetched zero" are different
    // claims, and a report summing the second would be wrong invisibly.
    assertThat(priceOf(tenant, itemId)).isNull();
  }

  @Test
  @DisplayName("refuses a price on something that was not sold")
  void onlyASaleHasAPrice() {
    Tenant tenant = newTenant("state-priced@example.org");
    UUID itemId = anItem(tenant, "A sofa");

    assertThatThrownBy(
            () ->
                inOwn(
                    tenant,
                    () ->
                        items.dispose(
                            itemId,
                            new ItemService.Disposal(
                                ItemState.DISPOSED,
                                Money.of("10.00", "EUR"),
                                LocalDate.parse("2026-09-12"),
                                null,
                                null),
                            OptionalLong.empty(),
                            tenant.userId())))
        .isInstanceOf(ItemStateException.class);

    assertThat(stateOf(tenant, itemId)).isEqualTo(ItemState.ACTIVE.name());
  }

  @Test
  @DisplayName("cannot be sold twice, because parting with a thing is terminal")
  void disposalIsTerminal() {
    Tenant tenant = newTenant("state-twice@example.org");
    UUID itemId = anItem(tenant, "A trailer");

    inOwn(
        tenant,
        () ->
            items.dispose(
                itemId,
                new ItemService.Disposal(
                    ItemState.SOLD, Money.of("80.00", "EUR"), LocalDate.parse("2026-09-01"), null, null),
                OptionalLong.empty(),
                tenant.userId()));

    // Unlike a trashing there is no way back: what is gone is the thing, not the
    // record of it.
    assertThatThrownBy(
            () ->
                inOwn(
                    tenant,
                    () ->
                        items.dispose(
                            itemId,
                            new ItemService.Disposal(
                                ItemState.DISPOSED, null, LocalDate.parse("2026-09-02"), null, null),
                            OptionalLong.empty(),
                            tenant.userId())))
        .isInstanceOf(ItemStateException.class);
  }

  @Test
  @DisplayName("refuses to lend out something that has been sold")
  void aSoldItemCannotBeLent() {
    Tenant tenant = newTenant("state-sold-lend@example.org");
    UUID itemId = anItem(tenant, "A tent");

    inOwn(
        tenant,
        () ->
            items.dispose(
                itemId,
                new ItemService.Disposal(
                    ItemState.SOLD, Money.of("60.00", "EUR"), LocalDate.parse("2026-09-01"), null, null),
                OptionalLong.empty(),
                tenant.userId()));

    // Not `item-lent`: nobody has it, and "ask for it back" is not the way out.
    assertThatThrownBy(
            () ->
                inOwn(
                    tenant,
                    () ->
                        loans.lend(
                            itemId,
                            new LoanLog.NewLoan(
                                null, "Anybody", LocalDate.parse("2026-09-05"), null, null),
                            tenant.userId())))
        .isInstanceOf(ItemStateException.class);
  }

  @Test
  @DisplayName("keeps the state and the deletion timestamp saying the same thing")
  void trashedIsTheStateAndTheTimestamp() {
    Tenant tenant = newTenant("state-trash@example.org");
    UUID itemId = anItem(tenant, "A lamp");

    inOwn(
        tenant,
        () -> {
          items.delete(itemId, OptionalLong.empty(), tenant.userId());
          return null;
        });

    // The database refuses a row where the two disagree (`item_trashed_iff_deleted`),
    // so this holds that the application writes both rather than relying on one.
    assertThat(stateOf(tenant, itemId)).isEqualTo(ItemState.TRASHED.name());
    assertThat(deletedAtOf(tenant, itemId)).isNotNull();

    inOwn(tenant, () -> items.restore(itemId, OptionalLong.empty(), tenant.userId()));
    assertThat(stateOf(tenant, itemId)).isEqualTo(ItemState.ACTIVE.name());
    assertThat(deletedAtOf(tenant, itemId)).isNull();
  }

  // -------------------------------------------------------------------------

  private String stateOf(Tenant tenant, UUID itemId) {
    return inOwn(
        tenant,
        () ->
            jdbc.sql("select lifecycle_state from inventory.item where tenant_id = ? and id = ?")
                .param(tenant.tenantId())
                .param(itemId)
                .query(String.class)
                .single());
  }

  private java.math.BigDecimal priceOf(Tenant tenant, UUID itemId) {
    return inOwn(
        tenant,
        () ->
            jdbc.sql("select disposal_amount from inventory.item where tenant_id = ? and id = ?")
                .param(tenant.tenantId())
                .param(itemId)
                .query(java.math.BigDecimal.class)
                .optional()
                .orElse(null));
  }

  private Instant deletedAtOf(Tenant tenant, UUID itemId) {
    return inOwn(
        tenant,
        () ->
            jdbc.sql("select deleted_at from inventory.item where tenant_id = ? and id = ?")
                .param(tenant.tenantId())
                .param(itemId)
                .query(Instant.class)
                .optional()
                .orElse(null));
  }

  private UUID anItem(Tenant tenant, String name) {
    return inOwn(
        tenant,
        () ->
            items
                .create(
                    new ItemService.CreateItemCommand(
                        null,
                        builtinType(tenant.tenantId()),
                        name,
                        null,
                        ItemKind.PHYSICAL,
                        aPlace(tenant),
                        java.math.BigDecimal.ONE,
                        null,
                        "{}",
                        null,
                        null,
                        de.greluc.homeinv.inventory.api.Valuation.NONE),
                    java.util.Optional.empty(),
                    tenant.userId())
                .item()
                .id());
  }

  private UUID aPlace(Tenant tenant) {
    return locations
        .create(
            new de.greluc.homeinv.locations.api.LocationService.CreateLocationCommand(
                null, anyCategory(tenant.tenantId()), null, "A room " + UUID.randomUUID(), null),
            java.util.Optional.empty(),
            tenant.userId())
        .id();
  }

  private UUID anyCategory(UUID tenantId) {
    return jdbc
        .sql("select id from catalog.location_category where tenant_id = ? and key = 'box'")
        .param(tenantId)
        .query(UUID.class)
        .single();
  }

  private UUID builtinType(UUID tenantId) {
    return jdbc
        .sql("select id from catalog.item_type where tenant_id = ? and key = 'general'")
        .param(tenantId)
        .query(UUID.class)
        .single();
  }

  private <T> T inOwn(Tenant tenant, Supplier<T> body) {
    return TenantContext.callAs(tenant.tenantId(), () -> transactions.execute(status -> body.get()));
  }

  private Tenant newTenant(String email) {
    UUID userId = UUID.randomUUID();
    transactions.executeWithoutResult(
        status ->
            users.save(
                AppUser.create(
                    userId, email, "Owner", "en", passwordEncoder.encode(PASSWORD), Instant.now())));
    enrolSecondFactor(userId);
    return new Tenant(userId, provisioning.provision("Tenant of " + email, userId));
  }

  private record Tenant(UUID userId, UUID tenantId) {}
}
