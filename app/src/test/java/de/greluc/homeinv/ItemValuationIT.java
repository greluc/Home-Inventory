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
import de.greluc.homeinv.inventory.api.ItemView;
import de.greluc.homeinv.inventory.api.Valuation;
import de.greluc.homeinv.platform.Money;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What an item cost, what covers it and what replacing it would cost (REQ-LIFE-001/002/014).
 *
 * <p>The figures are columns rather than type attributes, decided with the owner on 2026-09-13, so
 * that a report can sum them across every type. What this test holds to is the property that
 * follows from them being three: they are <b>independent</b>. REQ-LIFE-014 says it of the
 * replacement value in as many words — "never derived from" the current value — and a test is the
 * only thing that keeps a convenience from creeping in later.
 */
@DisplayName("An item's valuation")
class ItemValuationIT extends AbstractIntegrationTest {

  @Autowired private ItemService items;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private AppUserRepository users;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;

  @Test
  @DisplayName("records three independent figures, each with its own as-of date")
  void threeFiguresAndNoneDerived() {
    Tenant tenant = newTenant("valuation-three@example.org");

    Valuation recorded =
        new Valuation(
            Money.of("899.00", "EUR"),
            LocalDate.of(2023, 4, 1),
            "A camera shop in Freiburg",
            LocalDate.of(2026, 4, 1),
            false,
            // A thing worth 200 second-hand can cost 1100 to buy new, and an
            // insurer asks for the second number. Nothing derives one from the
            // other, which is what makes both worth storing.
            Money.of("1100.00", "EUR"),
            LocalDate.of(2026, 9, 1),
            Valuation.Provenance.MANUAL,
            Money.of("200.00", "EUR"),
            LocalDate.of(2026, 9, 1));

    UUID id = inTenant(tenant, () -> create(tenant, "Camera", recorded).id());
    Valuation read = inTenant(tenant, () -> items.get(id).valuation());

    assertThat(read.purchase()).isEqualTo(Money.of("899.00", "EUR"));
    assertThat(read.purchasedOn()).isEqualTo(LocalDate.of(2023, 4, 1));
    assertThat(read.purchaseSource()).isEqualTo("A camera shop in Freiburg");
    assertThat(read.warrantyUntil()).isEqualTo(LocalDate.of(2026, 4, 1));
    assertThat(read.lifetimeWarranty()).isFalse();
    assertThat(read.replacement()).isEqualTo(Money.of("1100.00", "EUR"));
    assertThat(read.replacementSource()).isEqualTo(Valuation.Provenance.MANUAL);
    assertThat(read.currentValue()).isEqualTo(Money.of("200.00", "EUR"));
    assertThat(read.currentValueAsOf()).isEqualTo(LocalDate.of(2026, 9, 1));
  }

  @Test
  @DisplayName("takes one figure without the others, because a person records what they know")
  void figuresArriveSeparately() {
    Tenant tenant = newTenant("valuation-partial@example.org");

    Valuation purchaseOnly =
        new Valuation(
            Money.of("49.90", "EUR"), LocalDate.of(2026, 1, 5), null, null, false, null, null,
            null, null, null);
    UUID id = inTenant(tenant, () -> create(tenant, "Kettle", purchaseOnly).id());

    Valuation read = inTenant(tenant, () -> items.get(id).valuation());
    assertThat(read.purchase()).isEqualTo(Money.of("49.90", "EUR"));
    assertThat(read.replacement()).isNull();
    assertThat(read.currentValue()).isNull();

    // And an edit replaces the set whole: a figure left out is one cleared, which
    // is the only rule under which "remove the purchase price" is sayable.
    inTenant(
        tenant,
        () ->
            items.update(
                id,
                new ItemService.UpdateItemCommand(
                    "Kettle",
                    null,
                    null,
                    BigDecimal.ONE,
                    null,
                    "{}",
                    null,
                    null,
                    new Valuation(
                        null, null, null, null, false,
                        Money.of("59.00", "EUR"),
                        LocalDate.of(2026, 9, 13),
                        Valuation.Provenance.PLUGIN,
                        null,
                        null)),
                OptionalLong.empty(),
                tenant.userId()));

    Valuation afterwards = inTenant(tenant, () -> items.get(id).valuation());
    assertThat(afterwards.purchase()).as("left out, therefore cleared").isNull();
    assertThat(afterwards.replacement()).isEqualTo(Money.of("59.00", "EUR"));
    assertThat(afterwards.replacementSource()).isEqualTo(Valuation.Provenance.PLUGIN);
  }

  @Test
  @DisplayName("refuses a warranty that both ends and lasts for life")
  void oneAnswerAboutTheWarranty() {
    Tenant tenant = newTenant("valuation-warranty@example.org");

    assertThatThrownBy(
            () ->
                new Valuation(
                    null, null, null, LocalDate.of(2030, 1, 1), true, null, null, null, null,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("two answers to one question");

    // A lifetime warranty carries no date, which is the point of the flag: a date
    // far in the future is a date somebody would eventually have to explain.
    Valuation lifetime =
        new Valuation(null, null, null, null, true, null, null, null, null, null);
    UUID id = inTenant(tenant, () -> create(tenant, "Cast iron pan", lifetime).id());
    Valuation read = inTenant(tenant, () -> items.get(id).valuation());
    assertThat(read.lifetimeWarranty()).isTrue();
    assertThat(read.warrantyUntil()).isNull();
  }

  @Test
  @DisplayName("keeps currencies apart, because a total of two is not a number")
  void currenciesDoNotMix() {
    Tenant tenant = newTenant("valuation-currencies@example.org");

    Valuation dollars =
        new Valuation(
            Money.of("1000.00", "USD"), null, null, null, false,
            Money.of("1200.00", "EUR"), null, Valuation.Provenance.MANUAL, null, null);
    UUID id = inTenant(tenant, () -> create(tenant, "Imported lens", dollars).id());

    Valuation read = inTenant(tenant, () -> items.get(id).valuation());
    assertThat(read.purchase().currencyCode()).isEqualTo("USD");
    assertThat(read.replacement().currencyCode()).isEqualTo("EUR");

    // The two cannot be added, and that is the whole of ADR-0025: there is no
    // rate here, and inventing one would be a total wrong in a way nobody sees.
    assertThatThrownBy(() -> read.purchase().plus(read.replacement()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // -------------------------------------------------------------------------

  private ItemView create(Tenant tenant, String name, Valuation valuation) {
    return items
        .create(
            new ItemService.CreateItemCommand(
                null,
                null,
                name,
                null,
                ItemKind.DIGITAL,
                null,
                BigDecimal.ONE,
                null,
                "{}",
                null,
                null,
                valuation),
            Optional.empty(),
            tenant.userId())
        .item();
  }

  private <T> T inTenant(Tenant tenant, Supplier<T> body) {
    return TenantContext.callAs(
        tenant.tenantId(), () -> transactions.execute(status -> body.get()));
  }

  private Tenant newTenant(String email) {
    UUID userId = UUID.randomUUID();
    transactions.executeWithoutResult(
        status ->
            users.save(
                AppUser.create(
                    userId,
                    email,
                    "Valuer",
                    "en",
                    passwordEncoder.encode("irrelevant"),
                    Instant.now())));
    return new Tenant(userId, provisioning.provision("Tenant of " + email, userId));
  }

  private record Tenant(UUID userId, UUID tenantId) {}
}
