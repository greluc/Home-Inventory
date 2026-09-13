/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.homeinv.identity.api.AccountAdministration;
import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.inventory.api.ItemKind;
import de.greluc.homeinv.inventory.api.ItemService;
import de.greluc.homeinv.inventory.api.Valuation;
import de.greluc.homeinv.platform.CallerContext;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.tenancy.api.QuotaAdministration;
import de.greluc.homeinv.tenancy.api.QuotaExceededException;
import de.greluc.homeinv.tenancy.api.QuotaGuard;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A tenant is bounded in what it may hold, and told so with both numbers (REQ-TEN-009).
 *
 * <p>The stock quotas are exercised through the item path, because that is the one with a real
 * caller today; stored bytes are claimed on the same mechanism from {@code media}, and the plugin
 * count on it again when the plugin runtime exists.
 */
@DisplayName("Quotas")
class QuotasIT extends AbstractIntegrationTest {

  @Autowired private ItemService items;
  @Autowired private QuotaGuard quotas;
  @Autowired private QuotaAdministration administration;
  @Autowired private AccountAdministration accounts;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private AppUserRepository users;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;

  @Test
  @DisplayName("refuse the item that would exceed them, with current and permitted")
  void theItemQuotaBites() {
    Tenant tenant = newTenant("quota-items@example.org");
    administration.setLimit(tenant.tenantId(), QuotaGuard.Quota.ITEM_COUNT, 2, tenant.userId());

    asTenant(
        tenant,
        () -> {
          anItem(tenant, "First");
          anItem(tenant, "Second");

          assertThatThrownBy(() -> anItem(tenant, "Third"))
              .isInstanceOfSatisfying(
                  QuotaExceededException.class,
                  refused -> {
                    assertThat(refused.getQuota()).isEqualTo(QuotaGuard.Quota.ITEM_COUNT);
                    assertThat(refused.getPermitted()).isEqualTo(2);
                    assertThat(refused.getCurrent()).isEqualTo(3);
                  });
        });

    // The refused claim was returned by the rollback: the counter still says two,
    // not three, so a retry after the limit is raised is not already over.
    asTenant(tenant, () -> assertThat(used(QuotaGuard.Quota.ITEM_COUNT)).isEqualTo(2));
  }

  @Test
  @DisplayName("give the count back when an item is finally removed, not when it is trashed")
  void purgeReleases() {
    Tenant tenant = newTenant("quota-purge@example.org");

    asTenant(
        tenant,
        () -> {
          UUID id = anItem(tenant, "Temporary");
          assertThat(used(QuotaGuard.Quota.ITEM_COUNT)).isEqualTo(1);

          items.delete(id, OptionalLong.empty(), tenant.userId());
          // Still counted: a trashed item is still a row and still carries its
          // attachments.
          assertThat(used(QuotaGuard.Quota.ITEM_COUNT)).isEqualTo(1);

          items.purge(id, OptionalLong.empty(), tenant.userId());
          assertThat(used(QuotaGuard.Quota.ITEM_COUNT)).isZero();
        });
  }

  @Test
  @DisplayName("fall back to the instance-wide default when nothing has been set")
  void theDefaultApplies() {
    Tenant tenant = newTenant("quota-default@example.org");

    assertThat(administration.limitsOf(tenant.tenantId())).isEmpty();
    asTenant(
        tenant,
        () ->
            assertThat(permitted(QuotaGuard.Quota.ITEM_COUNT))
                // The default from HOMEINV_QUOTA_ITEMS, which nothing in the test
                // profile overrides.
                .isEqualTo(100_000));

    administration.setLimit(tenant.tenantId(), QuotaGuard.Quota.ITEM_COUNT, 7, tenant.userId());
    assertThat(administration.limitsOf(tenant.tenantId()))
        .singleElement()
        .satisfies(limit -> assertThat(limit.permitted()).isEqualTo(7));
  }

  @Test
  @DisplayName("are set across the tenant boundary without the operator reaching into it")
  void theOperatorSetsThemFromOutside() {
    Tenant theirs = newTenant("quota-theirs@example.org");
    UUID operator = createUser("quota-operator@example.org");
    transactions.executeWithoutResult(
        status -> accounts.replaceEntitlements(operator, true, false, null, operator));

    // No tenant context at all, and the operator is a member of nothing: this is
    // the SECURITY DEFINER path of 07 §7.5 and the only one there is.
    assertThat(TenantContext.current()).isEmpty();
    administration.setLimit(theirs.tenantId(), QuotaGuard.Quota.STORAGE_BYTES, 4096, operator);

    assertThat(administration.limitsOf(theirs.tenantId()))
        .singleElement()
        .satisfies(
            limit -> {
              assertThat(limit.quota()).isEqualTo(QuotaGuard.Quota.STORAGE_BYTES);
              assertThat(limit.permitted()).isEqualTo(4096);
            });

    // And the tenant sees it as its own limit.
    asTenant(theirs, () -> assertThat(permitted(QuotaGuard.Quota.STORAGE_BYTES)).isEqualTo(4096));
  }

  // -------------------------------------------------------------------------

  /**
   * Runs an action as this tenant's owner, with both contexts established.
   *
   * <p>Both, because a quota claim reads the tenant from one and records the actor from the other.
   *
   * @param tenant whose inventory
   * @param action what to run
   */
  private void asTenant(Tenant tenant, Runnable action) {
    TenantContext.runAs(
        tenant.tenantId(),
        () ->
            CallerContext.runAs(
                new CallerContext.Caller(tenant.userId(), tenant.tenantId(), "OWNER"), action));
  }

  /**
   * How much of one quota is in use, read inside the current tenant context.
   *
   * @param quota which bound
   * @return the used amount
   */
  private long used(QuotaGuard.Quota quota) {
    return view(quota).used();
  }

  /**
   * What one quota permits, read inside the current tenant context.
   *
   * @param quota which bound
   * @return the permitted amount
   */
  private long permitted(QuotaGuard.Quota quota) {
    return view(quota).permitted();
  }

  /**
   * One quota's entry in this tenant's usage.
   *
   * @param quota which bound
   * @return its view
   */
  private QuotaGuard.QuotaView view(QuotaGuard.Quota quota) {
    return quotas.usage().stream()
        .filter(entry -> entry.quota() == quota)
        .findFirst()
        .orElseThrow();
  }

  /**
   * A digital item, which needs no place.
   *
   * @param tenant whose inventory
   * @param name what to call it
   * @return the item's id
   */
  private UUID anItem(Tenant tenant, String name) {
    return items
        .create(
            new ItemService.CreateItemCommand(
                null, null, name, null, ItemKind.DIGITAL, null, BigDecimal.ONE, null, null, null,
                null, Valuation.NONE), Optional.empty(),
            tenant.userId())
        .item()
        .id();
  }

  private Tenant newTenant(String email) {
    UUID userId = createUser(email);
    return new Tenant(userId, provisioning.provision("Tenant of " + email, userId));
  }

  private UUID createUser(String email) {
    UUID userId = UUID.randomUUID();
    transactions.executeWithoutResult(
        status ->
            users.save(
                AppUser.create(
                    userId,
                    email,
                    "Test",
                    "en",
                    passwordEncoder.encode("irrelevant"),
                    Instant.now())));
    return userId;
  }

  /** The identifiers a test needs. */
  private record Tenant(UUID userId, UUID tenantId) {}
}
