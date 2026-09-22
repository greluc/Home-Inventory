/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.inventory.api.ItemKind;
import de.greluc.homeinv.inventory.api.ItemService;
import de.greluc.homeinv.inventory.api.Valuation;
import de.greluc.homeinv.inventory.application.DepreciationRefresh;
import de.greluc.homeinv.platform.Money;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Straight-line depreciation, and the figures it must not touch (REQ-LIFE-009).
 *
 * <p>The calculation is a division and is not what this is about. What it is about is the three
 * provenances: a run that overwrote a number somebody typed would be the application disagreeing
 * with its owner overnight, and a value nobody can trace is a value nobody should quote.
 */
@DisplayName("Straight-line depreciation")
class DepreciationIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple-42";
  private static final Currency EUR = Currency.getInstance("EUR");

  @Autowired private DepreciationRefresh depreciation;
  @Autowired private ItemService items;
  @Autowired private AppUserRepository users;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private org.springframework.jdbc.core.simple.JdbcClient jdbc;

  @Test
  @DisplayName("does nothing at all until a tenant says how long something lasts")
  void noUsefulLifeMeansNoValue() {
    Tenant tenant = newTenant("depreciation-none@example.org");
    anItem(tenant, "A drill", new BigDecimal("300.00"), LocalDate.of(2024, 1, 1));

    // No shipped type carries a useful life, on purpose: how long a household's
    // furniture lasts is a judgement about that household, and inventing one
    // here would put this application's guess in somebody's insurance report.
    assertThat(refreshOn(tenant, LocalDate.of(2026, 1, 1))).isZero();
    assertThat(currentValue(tenant)).isNull();
  }

  @Test
  @DisplayName("writes a straight line from the purchase price, and nothing once the life is over")
  void aStraightLine() {
    Tenant tenant = newTenant("depreciation-line@example.org");
    usefulLifeMonths(tenant, 60);
    anItem(tenant, "A washing machine", new BigDecimal("600.00"), LocalDate.of(2024, 1, 1));

    // Two years in, three of five remain.
    assertThat(refreshOn(tenant, LocalDate.of(2026, 1, 1))).isEqualTo(1);
    assertThat(currentValue(tenant)).isEqualByComparingTo("360.0000");
    assertThat(currentSource(tenant)).isEqualTo("DEPRECIATION");
    assertThat(currentAsOf(tenant)).isEqualTo(LocalDate.of(2026, 1, 1));

    // Later, less.
    refreshOn(tenant, LocalDate.of(2027, 7, 1));
    assertThat(currentValue(tenant)).isEqualByComparingTo("180.0000");

    // Past the end of its life a thing is worth nothing, which is what a
    // straight line to zero means. The replacement value is the separate figure
    // for what buying it again would cost (REQ-LIFE-014), and it is untouched.
    refreshOn(tenant, LocalDate.of(2030, 1, 1));
    assertThat(currentValue(tenant)).isEqualByComparingTo("0.0000");
  }

  @Test
  @DisplayName("never overwrites a figure somebody typed")
  void aTypedValueIsLeftAlone() {
    Tenant tenant = newTenant("depreciation-manual@example.org");
    usefulLifeMonths(tenant, 60);
    UUID itemId = anItem(tenant, "A bicycle", new BigDecimal("900.00"), LocalDate.of(2024, 1, 1));

    // Somebody values it themselves.
    setCurrentValue(tenant, itemId, new BigDecimal("750.00"));
    assertThat(currentSource(tenant)).isEqualTo("MANUAL");

    // The run passes it by. This is the property the whole `current_source`
    // column exists for.
    assertThat(refreshOn(tenant, LocalDate.of(2026, 1, 1))).isZero();
    assertThat(currentValue(tenant)).isEqualByComparingTo("750.00");
    assertThat(currentSource(tenant)).isEqualTo("MANUAL");
  }

  @Test
  @DisplayName("keeps its own figure when a client writes the same number back")
  void writingTheSameNumberBackDoesNotClaimIt() {
    Tenant tenant = newTenant("depreciation-roundtrip@example.org");
    usefulLifeMonths(tenant, 60);
    UUID itemId = anItem(tenant, "A sofa", new BigDecimal("600.00"), LocalDate.of(2024, 1, 1));
    refreshOn(tenant, LocalDate.of(2026, 1, 1));

    // A client reads the item, changes its name and writes the whole thing back
    // -- sending the depreciated figure it was just given. Treating that as
    // somebody typing it would freeze the value for ever, because the run never
    // touches what a person owns.
    Valuation asRead = inOwn(tenant, () -> items.get(itemId).valuation());
    inOwn(
        tenant,
        () ->
            items.update(
                itemId,
                new ItemService.UpdateItemCommand(
                    "A rather good sofa", null, null, BigDecimal.ONE, null, "{}", null, null,
                    asRead),
                java.util.OptionalLong.empty(),
                tenant.userId()));

    assertThat(currentSource(tenant)).isEqualTo("DEPRECIATION");
    // And it keeps ageing.
    refreshOn(tenant, LocalDate.of(2027, 1, 1));
    assertThat(currentValue(tenant)).isEqualByComparingTo("240.0000");
  }

  @Test
  @DisplayName("takes its figure away again when the type stops saying how long anything lasts")
  void removingTheUsefulLifeRemovesTheValue() {
    Tenant tenant = newTenant("depreciation-undone@example.org");
    usefulLifeMonths(tenant, 60);
    anItem(tenant, "A lamp", new BigDecimal("120.00"), LocalDate.of(2024, 1, 1));
    refreshOn(tenant, LocalDate.of(2026, 1, 1));
    assertThat(currentValue(tenant)).isNotNull();

    usefulLifeMonths(tenant, null);

    // A figure nothing stands behind any more is worse than none: it reads as a
    // fact and is the last thing a rule that no longer exists said.
    assertThat(refreshOn(tenant, LocalDate.of(2026, 1, 1))).isEqualTo(1);
    assertThat(currentValue(tenant)).isNull();
    assertThat(currentSource(tenant)).isNull();
  }

  // -------------------------------------------------------------------------

  /**
   * Runs the refresh inside the tenant's own context.
   *
   * <p>Which is the only way it sees anything: the query reads under row-level security, so a run
   * outside a context finds no rows and reports nothing — quietly and correctly.
   *
   * @param tenant whose items
   * @param on the day to value as of
   * @return how many were written
   */
  private int refreshOn(Tenant tenant, LocalDate on) {
    return inOwn(tenant, () -> depreciation.refresh(on));
  }

  private void usefulLifeMonths(Tenant tenant, Integer months) {
    inOwn(
        tenant,
        () ->
            jdbc.sql("update catalog.item_type set useful_life_months = ? where key = 'general'")
                .param(months)
                .update());
  }

  private void setCurrentValue(Tenant tenant, UUID itemId, BigDecimal amount) {
    inOwn(
        tenant,
        () -> {
          Valuation stored = items.get(itemId).valuation();
          return items.update(
              itemId,
              new ItemService.UpdateItemCommand(
                  items.get(itemId).name(),
                  null,
                  null,
                  BigDecimal.ONE,
                  null,
                  "{}",
                  null,
                  null,
                  new Valuation(
                      stored.purchase(),
                      stored.purchasedOn(),
                      stored.purchaseSource(),
                      stored.warrantyUntil(),
                      stored.lifetimeWarranty(),
                      stored.replacement(),
                      stored.replacementAsOf(),
                      stored.replacementSource(),
                      new Money(amount, EUR),
                      LocalDate.of(2026, 1, 1),
                      null)),
              java.util.OptionalLong.empty(),
              tenant.userId());
        });
  }

  private BigDecimal currentValue(Tenant tenant) {
    return inOwn(
        tenant,
        () ->
            jdbc.sql("select current_amount from inventory.item where deleted_at is null limit 1")
                .query(BigDecimal.class)
                .optional()
                .orElse(null));
  }

  private String currentSource(Tenant tenant) {
    return inOwn(
        tenant,
        () ->
            jdbc.sql("select current_source from inventory.item where deleted_at is null limit 1")
                .query(String.class)
                .optional()
                .orElse(null));
  }

  private LocalDate currentAsOf(Tenant tenant) {
    return inOwn(
        tenant,
        () ->
            jdbc.sql("select current_as_of from inventory.item where deleted_at is null limit 1")
                .query(LocalDate.class)
                .optional()
                .orElse(null));
  }

  private UUID anItem(Tenant tenant, String name, BigDecimal price, LocalDate purchasedOn) {
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
                        ItemKind.DIGITAL,
                        null,
                        BigDecimal.ONE,
                        null,
                        "{}",
                        null,
                        null,
                        new Valuation(
                            new Money(price, EUR),
                            purchasedOn,
                            null,
                            null,
                            false,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null)),
                    Optional.empty(),
                    tenant.userId())
                .item()
                .id());
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
                    userId, email, "Valuer", "en", passwordEncoder.encode(PASSWORD),
                    Instant.now())));
    enrolSecondFactor(userId);
    return new Tenant(userId, provisioning.provision("Tenant of " + email, userId));
  }

  private record Tenant(UUID userId, UUID tenantId) {}
}
