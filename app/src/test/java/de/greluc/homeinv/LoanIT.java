/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.inventory.api.ItemKind;
import de.greluc.homeinv.inventory.api.ItemLentException;
import de.greluc.homeinv.inventory.api.ItemService;
import de.greluc.homeinv.inventory.api.LoanLog;
import de.greluc.homeinv.platform.NotFoundException;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
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
 * Lending (REQ-LIFE-005).
 *
 * <p>Four properties, and three of them are refusals. A thing is in one pair of hands, so a second
 * handover is refused; a lent thing cannot be trashed, because the loan row is the only record of
 * who to ask for it back; and another tenant's item is not found rather than forbidden. The fourth
 * is that a return closes the loan it names and leaves the history readable.
 */
@DisplayName("Lending")
class LoanIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple-42";

  @Autowired private LoanLog loans;
  @Autowired private ItemService items;
  @Autowired private de.greluc.homeinv.locations.api.LocationService locations;
  @Autowired private AppUserRepository users;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private ObjectMapper json;
  @Autowired private org.springframework.jdbc.core.simple.JdbcClient jdbc;

  @Test
  @DisplayName("records who has a thing, and recognises that it is out")
  void lendsAndRecognises() {
    Tenant tenant = newTenant("loan-basic@example.org");
    UUID itemId = anItem(tenant, "A cordless drill");

    assertThat(inOwn(tenant, () -> loans.openLoanOf(itemId))).isEmpty();

    LoanLog.LoanView loan =
        inOwn(tenant, () -> lend(itemId, null, "The neighbour", "2026-09-01", "2026-09-15"));

    assertThat(loan.isOpen()).isTrue();
    assertThat(loan.borrowerName()).isEqualTo("The neighbour");
    assertThat(loan.borrowerUserId()).isNull();
    // "Recognisable as such" is the open row and not a flag on the item: one
    // answer to the question, so there is no second one to fall out of step.
    assertThat(inOwn(tenant, () -> loans.openLoanOf(itemId))).isPresent();
    assertThat(inOwn(tenant, () -> loans.isLent(itemId))).isTrue();
  }

  @Test
  @DisplayName("lends to a member of the household as readily as to a stranger")
  void borrowerCanBeAMember() {
    Tenant tenant = newTenant("loan-member@example.org");
    UUID itemId = anItem(tenant, "A ladder");

    LoanLog.LoanView loan =
        inOwn(tenant, () -> lend(itemId, tenant.userId(), null, "2026-09-02", null));

    assertThat(loan.borrowerUserId()).isEqualTo(tenant.userId());
    assertThat(loan.borrowerName()).isNull();
    // No due date agreed, which is a lend and not an oversight -- so it is never
    // overdue, however long it is out.
    assertThat(loan.dueOn()).isNull();
    assertThat(loan.isOverdueOn(LocalDate.parse("2030-01-01"))).isFalse();
  }

  @Test
  @DisplayName("refuses to lend out something somebody already has")
  void oneOpenLoanPerItem() {
    Tenant tenant = newTenant("loan-twice@example.org");
    UUID itemId = anItem(tenant, "A trailer");

    inOwn(tenant, () -> lend(itemId, null, "First borrower", "2026-09-01", null));

    assertThatThrownBy(
            () -> inOwn(tenant, () -> lend(itemId, null, "Second borrower", "2026-09-02", null)))
        .isInstanceOf(ItemLentException.class);

    // And the first loan is untouched: the refusal changed nothing.
    List<LoanLog.LoanView> history = inOwn(tenant, () -> loans.loansOf(itemId, 50));
    assertThat(history).hasSize(1);
    assertThat(history.get(0).borrowerName()).isEqualTo("First borrower");
  }

  @Test
  @DisplayName("takes it back, and lets it go out again afterwards")
  void returnsAndRelends() {
    Tenant tenant = newTenant("loan-return@example.org");
    UUID itemId = anItem(tenant, "A pressure washer");
    UUID loanId = inOwn(tenant, () -> lend(itemId, null, "A friend", "2026-08-01", "2026-08-08")).id();

    LoanLog.LoanView closed =
        inOwn(tenant, () -> loans.returnItem(itemId, loanId, LocalDate.parse("2026-08-10"), tenant.userId()));

    assertThat(closed.isOpen()).isFalse();
    assertThat(closed.returnedOn()).isEqualTo(LocalDate.parse("2026-08-10"));
    assertThat(inOwn(tenant, () -> loans.isLent(itemId))).isFalse();

    // The partial index only covers the open ones, so the thing can go out again.
    inOwn(tenant, () -> lend(itemId, null, "Somebody else", "2026-09-01", null));
    assertThat(inOwn(tenant, () -> loans.loansOf(itemId, 50))).hasSize(2);
  }

  @Test
  @DisplayName("keeps the first return date when a return is recorded twice")
  void returningTwiceKeepsTheFirstDate() {
    Tenant tenant = newTenant("loan-return-twice@example.org");
    UUID itemId = anItem(tenant, "A tile cutter");
    UUID loanId = inOwn(tenant, () -> lend(itemId, null, "A friend", "2026-08-01", null)).id();

    inOwn(tenant, () -> loans.returnItem(itemId, loanId, LocalDate.parse("2026-08-10"), tenant.userId()));
    LoanLog.LoanView again =
        inOwn(tenant, () -> loans.returnItem(itemId, loanId, LocalDate.parse("2026-08-20"), tenant.userId()));

    // The first return is the true one: the thing was back on the 10th whatever
    // a second request says. Not an error -- what the caller wants is already so.
    assertThat(again.returnedOn()).isEqualTo(LocalDate.parse("2026-08-10"));
  }

  @Test
  @DisplayName("is overdue only once a date has passed, and never without one")
  void overdueNeedsADate() {
    Tenant tenant = newTenant("loan-overdue@example.org");
    UUID itemId = anItem(tenant, "A camera");
    LoanLog.LoanView loan =
        inOwn(tenant, () -> lend(itemId, null, "A colleague", "2026-08-01", "2026-08-15"));

    assertThat(loan.isOverdueOn(LocalDate.parse("2026-08-15"))).isFalse(); // due today, not late
    assertThat(loan.isOverdueOn(LocalDate.parse("2026-08-16"))).isTrue();
  }

  @Test
  @DisplayName("refuses to trash an item somebody else has")
  void aLentItemIsNotDeletable() {
    Tenant tenant = newTenant("loan-delete@example.org");
    UUID itemId = anItem(tenant, "A chainsaw");
    UUID loanId = inOwn(tenant, () -> lend(itemId, null, "The neighbour", "2026-09-01", null)).id();

    // REQ-LIFE-005: trashing it would take away the only record of who has it.
    assertThatThrownBy(
            () ->
                inOwn(
                    tenant,
                    () -> {
                      items.delete(itemId, OptionalLong.empty(), tenant.userId());
                      return null;
                    }))
        .isInstanceOf(ItemLentException.class);

    // Still there, and still lent: the refusal changed nothing either way.
    assertThat(inOwn(tenant, () -> loans.isLent(itemId))).isTrue();

    // Once it is back, the same call works.
    inOwn(tenant, () -> loans.returnItem(itemId, loanId, LocalDate.parse("2026-09-05"), tenant.userId()));
    inOwn(
        tenant,
        () -> {
          items.delete(itemId, OptionalLong.empty(), tenant.userId());
          return null;
        });
  }

  @Test
  @DisplayName("answers for another tenant's item the way it answers for one that never existed")
  void anotherTenantsItem() {
    Tenant mine = newTenant("loan-mine@example.org");
    Tenant theirs = newTenant("loan-theirs@example.org");
    UUID theirItem = anItem(theirs, "Their drill");

    // Not "forbidden": a foreign item is indistinguishable from one that never
    // existed (REQ-SEC-025), and the same answer covers both.
    assertThatThrownBy(
            () -> inOwn(mine, () -> lend(theirItem, null, "Me", "2026-09-01", null)))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> inOwn(mine, () -> loans.loansOf(theirItem, 50)))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  @DisplayName("refuses a return against a loan that belongs to a different item")
  void loanIdBelongsToItsItem() {
    Tenant tenant = newTenant("loan-crossed@example.org");
    UUID drill = anItem(tenant, "A drill");
    UUID saw = anItem(tenant, "A saw");
    UUID drillLoan = inOwn(tenant, () -> lend(drill, null, "A friend", "2026-09-01", null)).id();

    // The path names the saw; the loan is the drill's. Acting on it would close a
    // loan the request did not name (REQ-SEC-025).
    assertThatThrownBy(
            () ->
                inOwn(
                    tenant,
                    () -> loans.returnItem(saw, drillLoan, LocalDate.parse("2026-09-02"), tenant.userId())))
        .isInstanceOf(NotFoundException.class);
    assertThat(inOwn(tenant, () -> loans.isLent(drill))).isTrue();
  }

  @Test
  @DisplayName("over HTTP: lends, refuses a second handover with 409, and refuses the deletion")
  void overHttp() throws Exception {
    Tenant tenant = newTenant("loan-http@example.org");
    UUID itemId = anItem(tenant, "A generator");
    MockHttpSession session = signIn("loan-http@example.org", PASSWORD);

    mockMvc
        .perform(
            post("/api/v1/items/" + itemId + "/loans")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "borrowerName", "The neighbour",
                            "handedOutOn", "2026-09-01",
                            "dueOn", "2026-09-15"))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.returnedOn").doesNotExist());

    // A second handover is a 409 with the token a client can branch on, not a
    // 422 -- nothing about the request is wrong.
    mockMvc
        .perform(
            post("/api/v1/items/" + itemId + "/loans")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("borrowerName", "Somebody else", "handedOutOn", "2026-09-02"))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("/item-lent")));

    // And so is trashing it. With `If-Match`, because a write on a single resource
    // is refused with 428 without one (08 §8.2) -- and a 428 here would have said
    // nothing about lending.
    mockMvc
        .perform(
            delete("/api/v1/items/" + itemId)
                .session(session)
                .with(csrf())
                .header("If-Match", eTagOf(session, "/api/v1/items/" + itemId)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("/item-lent")));
  }

  // -------------------------------------------------------------------------

  private LoanLog.LoanView lend(
      UUID itemId, UUID borrowerUserId, String borrowerName, String handedOutOn, String dueOn) {
    return loans.lend(
        itemId,
        new LoanLog.NewLoan(
            borrowerUserId,
            borrowerName,
            LocalDate.parse(handedOutOn),
            dueOn == null ? null : LocalDate.parse(dueOn),
            null),
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

  /**
   * A place of its own for each item.
   *
   * <p>The name carries a random suffix because {@code location_sibling_name} is unique and this
   * test is the first here to give one tenant two items — which failed on the second shed rather
   * than on anything about lending.
   */
  private UUID aPlace(Tenant tenant) {
    return locations
        .create(
            new de.greluc.homeinv.locations.api.LocationService.CreateLocationCommand(
                null,
                anyCategory(tenant.tenantId()),
                null,
                "A shed " + UUID.randomUUID(),
                null),
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
                    userId, email, "Lender", "en", passwordEncoder.encode(PASSWORD), Instant.now())));
    enrolSecondFactor(userId);
    return new Tenant(userId, provisioning.provision("Tenant of " + email, userId));
  }

  private record Tenant(UUID userId, UUID tenantId) {}
}
