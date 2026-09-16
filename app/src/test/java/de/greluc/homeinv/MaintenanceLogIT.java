/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.inventory.api.ItemKind;
import de.greluc.homeinv.inventory.api.ItemService;
import de.greluc.homeinv.inventory.api.MaintenanceLog;
import de.greluc.homeinv.platform.Money;
import de.greluc.homeinv.platform.NotFoundException;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * The maintenance log (REQ-LIFE-003).
 *
 * <p>The property worth the most here is an <b>absence</b>: nothing edits an entry. A service
 * history that could be tidied up afterwards is worth nothing as a record, so the port offers no
 * edit, the controller exposes none, and the database grants no {@code UPDATE} — three independent
 * refusals, because a promise kept in one place only lasts until somebody adds a convenience
 * method.
 */
@DisplayName("The maintenance log")
class MaintenanceLogIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple-42";

  @Autowired private MaintenanceLog maintenance;
  @Autowired private ItemService items;
  @Autowired private de.greluc.homeinv.locations.api.LocationService locations;
  @Autowired private AppUserRepository users;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private ObjectMapper json;
  @Autowired private org.springframework.jdbc.core.simple.JdbcClient jdbc;

  @Test
  @DisplayName("lists the most recent work first, by when it was done rather than when it was typed")
  void mostRecentWorkFirst() {
    Tenant tenant = newTenant("maintenance-order@example.org");
    UUID itemId = anItem(tenant, "A bicycle");

    // Recorded out of order on purpose: somebody entering last year's invoice
    // today has not just serviced the bicycle.
    inOwn(tenant, () -> record(itemId, "2026-03-04", "Service", Money.of("89.90", "EUR"), null));
    inOwn(tenant, () -> record(itemId, "2026-09-01", "New tyres", Money.of("64.00", "EUR"), null));
    inOwn(tenant, () -> record(itemId, "2026-06-11", "Chain", null, "Replaced after 4000 km"));

    List<MaintenanceLog.MaintenanceEntryView> entries =
        inOwn(tenant, () -> maintenance.entriesOf(itemId, 50));

    assertThat(entries).hasSize(3);
    assertThat(entries.stream().map(MaintenanceLog.MaintenanceEntryView::performedOn))
        .containsExactly(
            LocalDate.parse("2026-09-01"), LocalDate.parse("2026-06-11"), LocalDate.parse("2026-03-04"));
  }

  @Test
  @DisplayName("keeps work that cost nothing apart from work that cost zero")
  void warrantyWorkHasNoCost() {
    Tenant tenant = newTenant("maintenance-warranty@example.org");
    UUID itemId = anItem(tenant, "A dishwasher");

    inOwn(tenant, () -> record(itemId, "2026-05-20", "Repair under warranty", null, "Pump replaced"));

    MaintenanceLog.MaintenanceEntryView entry =
        inOwn(tenant, () -> maintenance.entriesOf(itemId, 50)).get(0);
    // Absent rather than 0.00: "nothing was paid" and "it cost zero" are
    // different claims, and a report summing the second would be wrong in a way
    // nobody could see.
    assertThat(entry.cost()).isNull();
    assertThat(entry.note()).isEqualTo("Pump replaced");
  }

  @Test
  @DisplayName("carries the amount with its currency and never without")
  void costIsAlwaysWhole() {
    Tenant tenant = newTenant("maintenance-money@example.org");
    UUID itemId = anItem(tenant, "A car");

    inOwn(tenant, () -> record(itemId, "2026-07-07", "Inspektion", Money.of("249.00", "EUR"), null));

    MaintenanceLog.MaintenanceEntryView entry =
        inOwn(tenant, () -> maintenance.entriesOf(itemId, 50)).get(0);
    assertThat(entry.cost()).isEqualTo(Money.of("249.00", "EUR"));
  }

  @Test
  @DisplayName("removes an entry logged against the wrong thing, and says nothing about a second try")
  void removesAnEntry() {
    Tenant tenant = newTenant("maintenance-remove@example.org");
    UUID itemId = anItem(tenant, "A washing machine");
    UUID entryId =
        inOwn(tenant, () -> record(itemId, "2026-02-02", "Descaled", null, null)).id();

    inOwn(
        tenant,
        () -> {
          maintenance.remove(itemId, entryId, tenant.userId());
          return null;
        });
    assertThat(inOwn(tenant, () -> maintenance.entriesOf(itemId, 50))).isEmpty();

    // Removing what is not there is not an error: what the caller wants is
    // already true.
    inOwn(
        tenant,
        () -> {
          maintenance.remove(itemId, entryId, tenant.userId());
          return null;
        });
  }

  @Test
  @DisplayName("refuses to record against an item this tenant does not have")
  void anotherTenantsItem() {
    Tenant mine = newTenant("maintenance-mine@example.org");
    Tenant theirs = newTenant("maintenance-theirs@example.org");
    UUID theirItem = anItem(theirs, "Their bicycle");

    // Not "forbidden": a foreign item is indistinguishable from one that never
    // existed (REQ-SEC-025), and the same answer covers both.
    assertThatThrownBy(
            () -> inOwn(mine, () -> record(theirItem, "2026-01-01", "Anything", null, null)))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> inOwn(mine, () -> maintenance.entriesOf(theirItem, 50)))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  @DisplayName("exposes no way to edit an entry over HTTP either")
  void noEditEndpoint() throws Exception {
    Tenant tenant = newTenant("maintenance-http@example.org");
    UUID itemId = anItem(tenant, "A lawnmower");
    UUID entryId =
        inOwn(tenant, () -> record(itemId, "2026-04-02", "Blade sharpened", null, null)).id();
    MockHttpSession session = signIn("maintenance-http@example.org", PASSWORD);

    // The path is an entry that exists; what is refused is the method. A 405 and
    // not a 404 is the difference between "no such thing" and "not a thing you
    // do to this".
    mockMvc
        .perform(
            put("/api/v1/items/" + itemId + "/maintenance/" + entryId)
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"Something else\"}"))
        .andExpect(status().isMethodNotAllowed());

    // And the list is readable where it is written.
    mockMvc
        .perform(get("/api/v1/items/" + itemId + "/maintenance").session(session))
        .andExpect(status().isOk());

    // The HTTP path records one too, with the money shape REQ-NFR-070 asks for:
    // the amount as a string, never without its currency.
    mockMvc
        .perform(
            post("/api/v1/items/" + itemId + "/maintenance")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "performedOn",
                            "2026-08-08",
                            "kind",
                            "Oil change",
                            "cost",
                            Map.of("amount", "12.50", "currency", "EUR")))))
        .andExpect(status().isCreated());
  }

  // -------------------------------------------------------------------------

  private MaintenanceLog.MaintenanceEntryView record(
      UUID itemId, String performedOn, String kind, Money cost, String note) {
    return maintenance.record(
        itemId,
        new MaintenanceLog.NewMaintenanceEntry(LocalDate.parse(performedOn), kind, cost, note),
        UUID.randomUUID());
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
                        // A physical item resides in exactly one place, which the
                        // aggregate enforces rather than the database.
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
                null, anyCategory(tenant.tenantId()), null, "A shelf", null),
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
                    "Maintainer",
                    "en",
                    passwordEncoder.encode(PASSWORD),
                    Instant.now())));
    enrolSecondFactor(userId);
    return new Tenant(userId, provisioning.provision("Tenant of " + email, userId));
  }

  private record Tenant(UUID userId, UUID tenantId) {}
}
