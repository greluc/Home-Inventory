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
import de.greluc.homeinv.inventory.api.ValuationReport;
import de.greluc.homeinv.platform.Money;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What the things in here are worth (REQ-LIFE-008, REQ-LIFE-015, REQ-LIFE-017).
 *
 * <p>The properties are all about <b>refusing to mix</b>. Three figures stay apart because they are
 * independent (REQ-LIFE-014); currencies stay apart because REQ-LIFE-017 forbids a mixed total "not
 * even as an approximation"; and a room's own contents stay apart from its subtree's, because a
 * number whose meaning a reader has to guess is worse than two numbers.
 */
@DisplayName("The valuation report")
class ValuationReportIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple-42";

  @Autowired private ValuationReport reports;
  @Autowired private ItemService items;
  @Autowired private de.greluc.homeinv.locations.api.LocationService locations;
  @Autowired private AppUserRepository users;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private org.springframework.jdbc.core.simple.JdbcClient jdbc;

  @Test
  @DisplayName("keeps a room's own contents apart from what is in the boxes inside it")
  void ownAndSubtreeAreBothReported() {
    Tenant tenant = newTenant("report-rollup@example.org");
    UUID room = aPlace(tenant, "A room", null);
    UUID box = aPlace(tenant, "A box", room);

    anItem(tenant, "A lamp", room, purchase("100.00", "EUR"));
    anItem(tenant, "A drill", box, purchase("250.00", "EUR"));

    ValuationReport.Report report = inOwn(tenant, () -> reports.byLocation(null, 50));
    ValuationReport.Row roomRow = rowFor(report, room);

    // Directly in the room: the lamp. In the room including its boxes: both.
    assertThat(only(roomRow.own().purchase())).isEqualTo(Money.of("100.00", "EUR"));
    assertThat(only(roomRow.subtree().purchase())).isEqualTo(Money.of("350.00", "EUR"));
    assertThat(roomRow.ownCount()).isEqualTo(1);
    assertThat(roomRow.subtreeCount()).isEqualTo(2);

    // And the box says only what is in the box.
    ValuationReport.Row boxRow = rowFor(report, box);
    assertThat(only(boxRow.own().purchase())).isEqualTo(Money.of("250.00", "EUR"));
    assertThat(only(boxRow.subtree().purchase())).isEqualTo(Money.of("250.00", "EUR"));
  }

  @Test
  @DisplayName("shows one line per currency and never adds them together")
  void currenciesAreNeverMixed() {
    Tenant tenant = newTenant("report-currency@example.org");
    UUID room = aPlace(tenant, "A study", null);
    anItem(tenant, "A desk", room, purchase("300.00", "EUR"));
    anItem(tenant, "A monitor", room, purchase("200.00", "USD"));

    ValuationReport.Report report = inOwn(tenant, () -> reports.byLocation(null, 50));
    ValuationReport.Row row = rowFor(report, room);

    // Two lines, not one number. REQ-LIFE-017 forbids a mixed total "not even as
    // an approximation", and 500 of anything would be exactly that.
    assertThat(row.own().purchase())
        .containsExactlyInAnyOrder(Money.of("300.00", "EUR"), Money.of("200.00", "USD"));
    assertThat(report.currencies()).containsExactly("EUR", "USD");

    // And the report SAYS no conversion happened, rather than leaving it to be
    // assumed from the absence of one.
    assertThat(report.converted()).isFalse();
  }

  @Test
  @DisplayName("keeps the three figures apart and never derives one from another")
  void theThreeFiguresStayApart() {
    Tenant tenant = newTenant("report-figures@example.org");
    UUID room = aPlace(tenant, "A garage", null);
    anItem(
        tenant,
        "A bicycle",
        room,
        new Valuation(
            Money.of("900.00", "EUR"),
            java.time.LocalDate.parse("2024-05-01"),
            "A shop",
            null,
            false,
            Money.of("1200.00", "EUR"),
            java.time.LocalDate.parse("2026-01-01"),
            Valuation.Provenance.MANUAL,
            Money.of("400.00", "EUR"),
            java.time.LocalDate.parse("2026-01-01")));

    ValuationReport.Row row = rowFor(inOwn(tenant, () -> reports.byLocation(null, 50)), room);

    // What it cost, what replacing it would cost and what it is worth now are
    // three answers to three questions (REQ-LIFE-014/015).
    assertThat(only(row.own().purchase())).isEqualTo(Money.of("900.00", "EUR"));
    assertThat(only(row.own().replacement())).isEqualTo(Money.of("1200.00", "EUR"));
    assertThat(only(row.own().current())).isEqualTo(Money.of("400.00", "EUR"));
  }

  @Test
  @DisplayName("leaves out what has been sold, and what is in the trash")
  void whatIsGoneIsNotCounted() {
    Tenant tenant = newTenant("report-gone@example.org");
    UUID room = aPlace(tenant, "A shed", null);
    UUID kept = anItem(tenant, "A ladder", room, purchase("80.00", "EUR"));
    UUID sold = anItem(tenant, "A mower", room, purchase("300.00", "EUR"));

    inOwn(
        tenant,
        () ->
            items.dispose(
                sold,
                new ItemService.Disposal(
                    de.greluc.homeinv.inventory.api.ItemState.SOLD,
                    Money.of("150.00", "EUR"),
                    java.time.LocalDate.parse("2026-09-01"),
                    null,
                    null),
                java.util.OptionalLong.empty(),
                tenant.userId()));

    // "What is the shed worth" that counted the mower sold in March would be
    // wrong in a way nobody could see.
    ValuationReport.Row row = rowFor(inOwn(tenant, () -> reports.byLocation(null, 50)), room);
    assertThat(only(row.own().purchase())).isEqualTo(Money.of("80.00", "EUR"));
    assertThat(row.ownCount()).isEqualTo(1);
    assertThat(kept).isNotNull();
  }

  @Test
  @DisplayName("reports per type, and says that tag rows overlap")
  void byTypeAndByTag() {
    Tenant tenant = newTenant("report-type@example.org");
    UUID room = aPlace(tenant, "A room", null);
    anItem(tenant, "A chair", room, purchase("60.00", "EUR"));

    ValuationReport.Report byType = inOwn(tenant, () -> reports.byType(50));
    assertThat(byType.dimension()).isEqualTo("type");
    // Nothing overlaps: an item has exactly one type, so the column adds up.
    assertThat(byType.overlapping()).isFalse();
    assertThat(byType.rows()).isNotEmpty();
    assertThat(byType.rows().get(0).own()).isEqualTo(byType.rows().get(0).subtree());

    ValuationReport.Report byTag = inOwn(tenant, () -> reports.byTag(50));
    // A thing can carry several tags, so adding this column up gives a number
    // that means nothing -- and the report says so rather than letting a reader
    // find out.
    assertThat(byTag.overlapping()).isTrue();
    assertThat(byTag.converted()).isFalse();
  }

  @Test
  @DisplayName("narrows to one subtree when a root is named")
  void aRootNarrowsIt() {
    Tenant tenant = newTenant("report-root@example.org");
    UUID house = aPlace(tenant, "A house", null);
    UUID kitchen = aPlace(tenant, "A kitchen", house);
    UUID elsewhere = aPlace(tenant, "A caravan", null);

    anItem(tenant, "A kettle", kitchen, purchase("40.00", "EUR"));
    anItem(tenant, "A stove", elsewhere, purchase("500.00", "EUR"));

    ValuationReport.Report report = inOwn(tenant, () -> reports.byLocation(house, 50));

    assertThat(report.rows().stream().map(ValuationReport.Row::id))
        .contains(house, kitchen)
        .doesNotContain(elsewhere);
    assertThat(only(rowFor(report, house).subtree().purchase())).isEqualTo(Money.of("40.00", "EUR"));
  }

  // -------------------------------------------------------------------------

  private static Money only(List<Money> amounts) {
    assertThat(amounts).hasSize(1);
    return amounts.get(0);
  }

  private static ValuationReport.Row rowFor(ValuationReport.Report report, UUID id) {
    return report.rows().stream()
        .filter(row -> row.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no row for " + id + " in " + report.rows()));
  }

  private static Valuation purchase(String amount, String currency) {
    return new Valuation(
        Money.of(amount, currency),
        java.time.LocalDate.parse("2026-01-01"),
        null,
        null,
        false,
        null,
        null,
        null,
        null,
        null);
  }

  private UUID anItem(Tenant tenant, String name, UUID where, Valuation valuation) {
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
                        where,
                        BigDecimal.ONE,
                        null,
                        "{}",
                        null,
                        null,
                        valuation),
                    java.util.Optional.empty(),
                    tenant.userId())
                .item()
                .id());
  }

  private UUID aPlace(Tenant tenant, String name, UUID parent) {
    return inOwn(
        tenant,
        () ->
            locations
                .create(
                    new de.greluc.homeinv.locations.api.LocationService.CreateLocationCommand(
                        null, anyCategory(tenant.tenantId()), parent, name + " " + UUID.randomUUID(), null),
                    java.util.Optional.empty(),
                    tenant.userId())
                .id());
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
