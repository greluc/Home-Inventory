/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.homeinv.authorization.api.Role;
import de.greluc.homeinv.authorization.api.RoleAdministration;
import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.inventory.api.ItemKind;
import de.greluc.homeinv.inventory.api.ItemService;
import de.greluc.homeinv.locations.api.LocationService;
import de.greluc.homeinv.platform.CallerContext;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.platform.TenantErasure;
import de.greluc.homeinv.tagging.api.TagService;
import de.greluc.homeinv.tenancy.api.ErasureCertificates;
import de.greluc.homeinv.tenancy.api.MembershipAdministration;
import de.greluc.homeinv.tenancy.api.TenantLifecycle;
import de.greluc.homeinv.tenancy.application.TenantErasureRunner;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The second half of an erasure: the blocks remove their share and a certificate is issued
 * (REQ-TEN-011, REQ-PRIV-005).
 *
 * <p>The request is backdated rather than the grace period shortened. The shipped thirty days are
 * then the ones under test, and nothing here depends on a setting that could drift from the
 * default.
 *
 * <p>The tenant is given something in every block that holds a reference into another — an item in
 * a place of a type, a tag on that item, a member confined to that place holding a tenant-owned
 * role. An empty tenant erases in any order at all; this one erases only in the right one, which is
 * what {@link TenantErasure#order()} is for.
 */
@DisplayName("Erasing a tenant")
class TenantErasureRunIT extends AbstractIntegrationTest {

  @Autowired private TenantErasureRunner runner;
  @Autowired private ErasureCertificates certificates;
  @Autowired private TenantLifecycle lifecycle;
  @Autowired private ItemService items;
  @Autowired private LocationService locations;
  @Autowired private TagService tags;
  @Autowired private RoleAdministration roles;
  @Autowired private MembershipAdministration members;
  @Autowired private AppUserRepository users;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private JdbcClient jdbc;

  @Test
  @DisplayName("removes every block's share, keeps the audit log, and says so in the certificate")
  void everyBlockReports() {
    Tenant tenant = newTenant("erasure-run@example.org");
    fill(tenant);

    asOwner(tenant, () -> lifecycle.requestDeletion(tenant.userId()));
    backdateRequest(tenant);

    assertThat(runner.eraseDueTenants()).isPositive();

    // The rows are gone. Read under the tenant's own context, which is the only
    // context that could still see them.
    assertThat(rowsIn(tenant, "inventory.item")).isZero();
    assertThat(rowsIn(tenant, "tagging.tag_assignment")).isZero();
    assertThat(rowsIn(tenant, "tagging.tag")).isZero();
    assertThat(rowsIn(tenant, "locations.location")).isZero();
    assertThat(rowsIn(tenant, "catalog.item_type")).isZero();
    assertThat(rowsIn(tenant, "authz.role_definition")).isZero();
    assertThat(rowsIn(tenant, "tenancy.membership")).isZero();

    // The tenant row stays, as the tombstone the certificate points at, and it
    // says what it is.
    assertThat(stateOf(tenant)).isEqualTo("ERASED");

    ErasureCertificates.Certificate certificate =
        certificates.forTenant(tenant.tenantId()).orElseThrow();

    assertThat(certificate.tenantName()).isNotBlank();
    assertThat(certificate.requestedBy()).isEqualTo(tenant.userId());

    // In the order the run went, which is the order the foreign keys allow.
    // `tenancy` is deliberately not last: a membership names the location a
    // member is confined to and the role definition they hold (V28, V26), so it
    // has to go before both of those blocks.
    assertThat(certificate.report())
        .extracting(TenantErasure.BlockReport::block)
        .containsExactly(
            "idempotency", "media", "tagging", "inventory", "tenancy", "locations", "catalog",
            "authorization", "audit");

    // And the blocks that held something say how much.
    assertThat(reportOf(certificate, "inventory").rowsRemoved()).isPositive();
    assertThat(reportOf(certificate, "locations").rowsRemoved()).isPositive();
    assertThat(reportOf(certificate, "tenancy").rowsRemoved()).isPositive();

    // The audit log is the one entry that always carries a note: the application
    // may not delete it (REQ-SEC-069) and the certificate says so rather than
    // implying it went.
    assertThat(reportOf(certificate, "audit").rowsRemoved()).isZero();
    assertThat(reportOf(certificate, "audit").note()).contains("REQ-SEC-069").contains("retained");
  }

  @Test
  @DisplayName("issues one certificate however often the run is repeated")
  void theRunIsRepeatable() {
    Tenant tenant = newTenant("erasure-twice@example.org");
    asOwner(tenant, () -> lifecycle.requestDeletion(tenant.userId()));
    backdateRequest(tenant);

    runner.eraseDueTenants();
    Instant first = certificates.forTenant(tenant.tenantId()).orElseThrow().completedAt();

    // The tenant is no longer due — the tombstone took it off the list — so a
    // second sweep finds nothing and changes nothing.
    runner.eraseDueTenants();
    assertThat(certificates.forTenant(tenant.tenantId()).orElseThrow().completedAt())
        .isEqualTo(first);
  }

  // -------------------------------------------------------------------------

  /**
   * Gives the tenant one row in each block that references another.
   *
   * <p>A shelf inside a cellar, a physical item on the shelf, a tag on the item, a tenant-owned
   * role, and the owner confined to the shelf while holding it. Between them they cover every
   * cross-block foreign key an erasure can trip over.
   *
   * @param tenant whose rows
   */
  private void fill(Tenant tenant) {
    asOwner(
        tenant,
        () -> {
          UUID category = aCategory(tenant);
          UUID cellar = locations
              .create(
                  new LocationService.CreateLocationCommand(null, category, null, "Cellar", null), Optional.empty(),
                  tenant.userId())
              .id();
          UUID shelf = locations
              .create(
                  new LocationService.CreateLocationCommand(null, category, cellar, "Shelf", null), Optional.empty(),
                  tenant.userId())
              .id();

          UUID item =
              items
                  .create(
                      new ItemService.CreateItemCommand(
                          null, null, "A thing", null, ItemKind.PHYSICAL, shelf, BigDecimal.ONE,
                          null, null, null, null), Optional.empty(),
                      tenant.userId())
                  .item()
                  .id();

          UUID tag =
              tags.create(
                      new TagService.CreateTagCommand("Fragile", null, null, null),
                      tenant.userId())
                  .id();
          tags.assign(tag, TagService.TagTarget.ITEM, item, tenant.userId());

          UUID role =
              roles
                  .create("Keeper", "Looks after the shelf", Role.OWNER, Set.of(),
                      tenant.userId())
                  .id();
          members.changeRole(tenant.userId(), "OWNER", role, shelf, tenant.userId());
        });
  }

  /**
   * One of the location categories provisioning seeded.
   *
   * @param tenant whose set
   * @return a category id
   */
  private UUID aCategory(Tenant tenant) {
    return inTenant(
        tenant,
        () ->
            jdbc.sql("select id from catalog.location_category order by key limit 1")
                .query((rs, rowNum) -> rs.getObject(1, UUID.class))
                .single());
  }

  /**
   * Moves the request past its grace period.
   *
   * <p>The alternative is a shorter period from a setting, and then the thirty days the requirement
   * names would be the one thing not under test.
   *
   * @param tenant whose request
   */
  private void backdateRequest(Tenant tenant) {
    inTenant(
        tenant,
        () ->
            jdbc.sql(
                    "update tenancy.tenant set deletion_requested_at = now() - interval '31 days'")
                .update());
  }

  /**
   * What the tenant row says it is.
   *
   * @param tenant whose row
   * @return the lifecycle state
   */
  private String stateOf(Tenant tenant) {
    return inTenant(
        tenant,
        () ->
            jdbc.sql("select lifecycle_state from tenancy.tenant")
                .query(String.class)
                .single());
  }

  /**
   * How many rows of a table one tenant still has.
   *
   * @param tenant whose rows
   * @param table the qualified table name
   * @return the count, read under that tenant's own context
   */
  private long rowsIn(Tenant tenant, String table) {
    return inTenant(tenant, () -> jdbc.sql("select count(*) from " + table).query(Long.class)
        .single());
  }

  /**
   * One block's line of a certificate.
   *
   * @param certificate the certificate
   * @param block the building block's name
   * @return its entry
   */
  private TenantErasure.BlockReport reportOf(
      ErasureCertificates.Certificate certificate, String block) {
    return certificate.report().stream()
        .filter(entry -> block.equals(entry.block()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("The certificate has no entry for " + block));
  }

  /**
   * Runs a statement inside one tenant's context and one transaction.
   *
   * <p>Both are needed: {@code app.tenant_id} is published when a transaction begins, so the same
   * statement outside one runs with no tenant set and the policy hides every row.
   *
   * @param tenant whose context
   * @param work what to run
   * @param <T> what it produces
   * @return what it produced
   */
  private <T> T inTenant(Tenant tenant, Supplier<T> work) {
    return TenantContext.callAs(
        tenant.tenantId(), () -> transactions.execute(status -> work.get()));
  }

  private void asOwner(Tenant tenant, Runnable action) {
    TenantContext.runAs(
        tenant.tenantId(),
        () ->
            CallerContext.runAs(
                new CallerContext.Caller(tenant.userId(), tenant.tenantId(), "OWNER"), action));
  }

  private Tenant newTenant(String email) {
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
    return new Tenant(userId, provisioning.provision("Tenant of " + email, userId));
  }

  /** The identifiers a test needs. */
  private record Tenant(UUID userId, UUID tenantId) {}
}
