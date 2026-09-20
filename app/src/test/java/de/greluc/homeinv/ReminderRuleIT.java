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
import de.greluc.homeinv.inventory.api.LoanLog;
import de.greluc.homeinv.notification.api.Notifications;
import de.greluc.homeinv.notification.api.ReminderRules;
import de.greluc.homeinv.notification.api.ReminderTrigger;
import de.greluc.homeinv.notification.api.UnservedTriggerException;
import de.greluc.homeinv.notification.application.ReminderRunner;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reminder rules (REQ-NOTI-001, REQ-NOTI-003, REQ-LIFE-006, REQ-LIFE-013).
 *
 * <p>The properties worth holding are the ones a reminder feature is usually got wrong on:
 *
 * <ul>
 *   <li>it fires <b>once</b>. A rule that repeats every time the scheduler runs is one people
 *       switch off, which is worse than one that never fired;
 *   <li>it fires again when the <b>date moves</b>, because a new date is a new thing to be told
 *       about;
 *   <li>it reaches only people who <b>asked</b> for it (REQ-NOTI-006);
 *   <li>a trigger nothing can answer is refused when the rule is <b>written</b>, not silently
 *       ignored when it runs.
 * </ul>
 */
@DisplayName("Reminder rules")
class ReminderRuleIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple-42";
  private static final String CHANNEL = "email";

  @Autowired private ReminderRules rules;
  @Autowired private ReminderRunner runner;
  @Autowired private Notifications notifications;
  @Autowired private ItemService items;
  @Autowired private LoanLog loans;
  @Autowired private de.greluc.homeinv.locations.api.LocationService locations;
  @Autowired private AppUserRepository users;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private org.springframework.jdbc.core.simple.JdbcClient jdbc;

  @Test
  @DisplayName("warns before a warranty runs out, once, and again when the date moves")
  void warrantyExpiryFiresOnce() {
    Tenant tenant = newTenant("reminder-warranty@example.org");
    UUID itemId = anItem(tenant, "A washing machine");
    warrantyUntil(tenant, itemId, "2026-10-01");
    subscribe(tenant, ReminderTrigger.WARRANTY_EXPIRY);

    UUID ruleId =
        inOwn(tenant, () -> rules.create(rule("Warranties", ReminderTrigger.WARRANTY_EXPIRY, 14), tenant.userId())).id();

    // Fifteen days out: outside the fortnight, so nothing yet.
    assertThat(run(tenant, "2026-09-16")).isZero();

    // Fourteen days out: due.
    assertThat(run(tenant, "2026-09-17")).isEqualTo(1);

    // And not again tomorrow, or ever, for the same date. This is the property a
    // reminder feature lives or dies by.
    assertThat(run(tenant, "2026-09-18")).isZero();
    assertThat(run(tenant, "2026-09-30")).isZero();

    // Unless the date moves: an extended warranty is a new thing to be told about.
    warrantyUntil(tenant, itemId, "2027-01-15");
    assertThat(run(tenant, "2027-01-02")).isEqualTo(1);

    assertThat(queuedFor(tenant)).hasSize(2);
    assertThat(ruleId).isNotNull();
  }

  @Test
  @DisplayName("tells nobody who did not ask to be told")
  void onlySubscribersHear() {
    Tenant tenant = newTenant("reminder-unsubscribed@example.org");
    UUID itemId = anItem(tenant, "A dishwasher");
    warrantyUntil(tenant, itemId, "2026-10-01");
    // Deliberately no subscription.

    inOwn(tenant, () -> rules.create(rule("Warranties", ReminderTrigger.WARRANTY_EXPIRY, 14), tenant.userId()));

    // The reminder is still RECORDED -- it was due, and a subscription appearing
    // tomorrow should not produce a backlog of everything that was ever due --
    // but nothing is queued for anybody.
    assertThat(run(tenant, "2026-09-20")).isZero();
    assertThat(queuedFor(tenant)).isEmpty();
  }

  @Test
  @DisplayName("waits until after the due date when the offset is negative (REQ-LIFE-006)")
  void overdueLoansFireAfterTheDate() {
    Tenant tenant = newTenant("reminder-overdue@example.org");
    UUID itemId = anItem(tenant, "A ladder");
    subscribe(tenant, ReminderTrigger.LOAN_DUE);
    inOwn(
        tenant,
        () ->
            loans.lend(
                itemId,
                new LoanLog.NewLoan(
                    null, "A neighbour", LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-10"), null),
                tenant.userId()));

    // -1 means "the day after it was due", which is what an OVERDUE reminder is.
    inOwn(tenant, () -> rules.create(rule("Overdue loans", ReminderTrigger.LOAN_DUE, -1), tenant.userId()));

    assertThat(run(tenant, "2026-09-10")).isZero(); // due today, not late
    assertThat(run(tenant, "2026-09-11")).isEqualTo(1);
    assertThat(run(tenant, "2026-09-12")).isZero(); // and not every day after
  }

  @Test
  @DisplayName("never reminds about a loan nobody put a date on")
  void aLoanWithNoDateIsNeverDue() {
    Tenant tenant = newTenant("reminder-nodate@example.org");
    UUID itemId = anItem(tenant, "A wheelbarrow");
    subscribe(tenant, ReminderTrigger.LOAN_DUE);
    inOwn(
        tenant,
        () ->
            loans.lend(
                itemId,
                new LoanLog.NewLoan(null, "A friend", LocalDate.parse("2026-09-01"), null, null),
                tenant.userId()));

    inOwn(tenant, () -> rules.create(rule("Overdue loans", ReminderTrigger.LOAN_DUE, 0), tenant.userId()));

    // There is nothing for it to be late against, and treating "no date" as "due
    // now" would make a reminder out of a lend nobody put a date on.
    assertThat(run(tenant, "2027-01-01")).isZero();
  }

  @Test
  @DisplayName("narrows to the things its saved search matches, and not the rest")
  void theSavedSearchNarrows() {
    Tenant tenant = newTenant("reminder-narrowed@example.org");
    UUID watched = anItem(tenant, "Zzz watched appliance");
    UUID ignored = anItem(tenant, "Something else entirely");
    warrantyUntil(tenant, watched, "2026-10-01");
    warrantyUntil(tenant, ignored, "2026-10-01");
    subscribe(tenant, ReminderTrigger.WARRANTY_EXPIRY);

    UUID searchId =
        inOwn(
                tenant,
                () ->
                    searches()
                        .create(
                            new de.greluc.homeinv.search.api.SavedSearches.SaveSearchCommand(
                                "Watched", "Zzz", List.of(), null),
                            tenant.userId()))
            .id();

    inOwn(
        tenant,
        () ->
            rules.create(
                new ReminderRules.NewReminderRule(
                    "Watched warranties", ReminderTrigger.WARRANTY_EXPIRY, searchId, 14, CHANNEL, true),
                tenant.userId()));

    // One of the two, not both: the search is what decides which.
    assertThat(run(tenant, "2026-09-20")).isEqualTo(1);
    assertThat(queuedFor(tenant)).hasSize(1);
    assertThat(queuedFor(tenant).get(0)).contains("Zzz watched appliance");
    assertThat(ignored).isNotNull();
  }

  @Test
  @DisplayName("refuses a trigger nothing here can answer, when the rule is written")
  void anUnservedTriggerIsRefusedOnSave() {
    Tenant tenant = newTenant("reminder-unserved@example.org");

    // Stocktaking is stage 2. Accepting this and then never firing would look
    // like working software until the day somebody needed it.
    assertThatThrownBy(
            () ->
                inOwn(
                    tenant,
                    () ->
                        rules.create(
                            rule("Stocktakes", ReminderTrigger.STOCKTAKE_DISCREPANCY, 0),
                            tenant.userId())))
        .isInstanceOf(UnservedTriggerException.class)
        .hasMessageContaining("WARRANTY_EXPIRY");

    assertThat(inOwn(tenant, () -> rules.servedTriggers()))
        .contains(ReminderTrigger.WARRANTY_EXPIRY, ReminderTrigger.LOAN_DUE, ReminderTrigger.MINIMUM_STOCK)
        .doesNotContain(ReminderTrigger.STOCKTAKE_DISCREPANCY, ReminderTrigger.LICENCE_EXPIRY);
  }

  @Test
  @DisplayName("stops firing when it is disabled, and keeps what was written")
  void aDisabledRuleDoesNothing() {
    Tenant tenant = newTenant("reminder-disabled@example.org");
    UUID itemId = anItem(tenant, "A boiler");
    warrantyUntil(tenant, itemId, "2026-10-01");
    subscribe(tenant, ReminderTrigger.WARRANTY_EXPIRY);

    ReminderRules.ReminderRuleView created =
        inOwn(tenant, () -> rules.create(rule("Warranties", ReminderTrigger.WARRANTY_EXPIRY, 14), tenant.userId()));
    inOwn(
        tenant,
        () ->
            rules.update(
                created.id(),
                new ReminderRules.NewReminderRule(
                    created.name(), created.trigger(), null, created.offsetDays(), CHANNEL, false),
                java.util.OptionalLong.empty(),
                tenant.userId()));

    assertThat(run(tenant, "2026-09-20")).isZero();
    // The rule is still there with what was written in it, so turning it back on
    // does not ask again.
    ReminderRules.ReminderRuleView stored = inOwn(tenant, () -> rules.get(created.id()));
    assertThat(stored.enabled()).isFalse();
    assertThat(stored.offsetDays()).isEqualTo(14);
  }

  @Test
  @DisplayName("stores no offset for a trigger that has no date")
  void anUndatedTriggerHasNoOffset() {
    Tenant tenant = newTenant("reminder-stock@example.org");

    ReminderRules.ReminderRuleView stored =
        inOwn(tenant, () -> rules.create(rule("Low stock", ReminderTrigger.MINIMUM_STOCK, 30), tenant.userId()));

    // "Three days before the coffee runs out" is not a thing anybody can know, so
    // the offset is normalised away rather than kept and ignored -- a rule whose
    // screen says "30 days early" and behaves otherwise is a rule that lies.
    assertThat(stored.offsetDays()).isZero();
  }

  // -------------------------------------------------------------------------

  private de.greluc.homeinv.search.api.SavedSearches searches() {
    return applicationContext.getBean(de.greluc.homeinv.search.api.SavedSearches.class);
  }

  @Autowired private org.springframework.context.ApplicationContext applicationContext;

  private ReminderRules.NewReminderRule rule(String name, ReminderTrigger trigger, int offsetDays) {
    return new ReminderRules.NewReminderRule(name, trigger, null, offsetDays, CHANNEL, true);
  }

  private int run(Tenant tenant, String today) {
    return TenantContext.callAs(
        tenant.tenantId(), () -> runner.runFor(tenant.tenantId(), LocalDate.parse(today)));
  }

  private void subscribe(Tenant tenant, ReminderTrigger trigger) {
    String kind = "reminder." + trigger.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    inOwn(
        tenant,
        () ->
            notifications.subscribe(
                tenant.userId(), kind, CHANNEL, "someone@example.org", true));
  }

  private List<String> queuedFor(Tenant tenant) {
    return inOwn(
        tenant,
        () ->
            jdbc
                .sql("select subject from notification.notification where tenant_id = ? order by created_at")
                .param(tenant.tenantId())
                .query(String.class)
                .list());
  }

  private void warrantyUntil(Tenant tenant, UUID itemId, String date) {
    inOwn(
        tenant,
        () ->
            jdbc
                .sql("update inventory.item set warranty_until = cast(? as date) where tenant_id = ? and id = ?")
                .param(date)
                .param(tenant.tenantId())
                .param(itemId)
                .update());
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
                null, anyCategory(tenant.tenantId()), null, "A place " + UUID.randomUUID(), null),
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
