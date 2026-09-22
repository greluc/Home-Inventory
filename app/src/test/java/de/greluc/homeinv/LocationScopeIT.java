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
import de.greluc.homeinv.inventory.api.Valuation;
import de.greluc.homeinv.locations.api.LocationService;
import de.greluc.homeinv.authorization.api.AccessControl;
import de.greluc.homeinv.authorization.api.Permission;
import de.greluc.homeinv.platform.CallerContext;
import de.greluc.homeinv.platform.NotFoundException;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.search.api.SearchService;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A membership confined to part of the tree sees nothing beyond it (REQ-TEN-007, ADR-0059).
 *
 * <h2>What is actually being proved</h2>
 *
 * <p>Not that one endpoint filters, but that the <b>database</b> does. Every read below goes
 * through the ordinary service methods with no scope argument anywhere — which is precisely the
 * shape of the query that would leak if the subtree lived only in the application layer. The
 * session's scope reaches them through {@code app.location_scope}, published beside
 * {@code app.tenant_id}, and the policies do the rest.
 */
@DisplayName("A scoped membership")
class LocationScopeIT extends AbstractIntegrationTest {

  @Autowired private AccessControl access;
  @Autowired private LocationService locations;
  @Autowired private ItemService items;
  @Autowired private SearchService search;
  @Autowired private AppUserRepository users;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private org.springframework.jdbc.core.simple.JdbcClient jdbc;

  @Test
  @DisplayName("reads its own subtree and nothing else, through queries that filter nothing")
  void seesOnlyItsOwnSubtree() {
    Tenant tenant = newTenant("scope-read@example.org");

    Places places = aHouseWithAGarage(tenant);

    // Unscoped, everything is there.
    unscoped(
        tenant,
        () -> {
          assertThat(locations.list(null, 50).data()).hasSize(3);
          assertThat(search.query(new SearchService.SearchRequest("", "en", null, null, null, List.of(), List.of(), 50)).data()).hasSize(2);
        });

    // Confined to the garage: one place, one item, and the ordinary list methods
    // are the ones being asked.
    scoped(
        tenant,
        places.garage(),
        () -> {
          assertThat(locations.list(null, 50).data())
              .singleElement()
              .satisfies(place -> assertThat(place.id()).isEqualTo(places.garage()));

          assertThat(search.query(new SearchService.SearchRequest("", "en", null, null, null, List.of(), List.of(), 50)).data())
              .singleElement()
              .satisfies(item -> assertThat(item.id()).isEqualTo(places.inGarage()));

          // By id, the room upstairs is simply not there — which is what a place
          // somebody may not see looks like to them (REQ-SEC-025).
          assertThatThrownBy(() -> locations.get(places.bedroom()))
              .isInstanceOf(NotFoundException.class);
          assertThatThrownBy(() -> items.get(places.inBedroom()))
              .isInstanceOf(NotFoundException.class);

          // And its own is.
          assertThat(items.get(places.inGarage()).id()).isEqualTo(places.inGarage());
        });
  }

  @Test
  @DisplayName("cannot put anything outside its subtree, and is told so rather than failing")
  void cannotWriteOutside() {
    Tenant tenant = newTenant("scope-write@example.org");
    Places places = aHouseWithAGarage(tenant);

    scoped(
        tenant,
        places.garage(),
        () -> {
          // A place under a parent it cannot see.
          assertThatThrownBy(
                  () ->
                      locations.create(
                          new LocationService.CreateLocationCommand(
                              null, categoryOf(tenant), places.bedroom(), "Sneaky", null), Optional.empty(),
                          tenant.userId()))
              .isInstanceOf(NotFoundException.class);

          // A root, which is outside every subtree including its own.
          assertThatThrownBy(
                  () ->
                      locations.create(
                          new LocationService.CreateLocationCommand(
                              null, categoryOf(tenant), null, "A second house", null), Optional.empty(),
                          tenant.userId()))
              .isInstanceOf(NotFoundException.class);

          // An item in a place it cannot see.
          assertThatThrownBy(
                  () ->
                      items.create(
                          new ItemService.CreateItemCommand(
                              null,
                              null,
                              "Misplaced",
                              null,
                              ItemKind.PHYSICAL,
                              places.bedroom(),
                              BigDecimal.ONE,
                              null,
                              null,
                              null,
                              null, Valuation.NONE), Optional.empty(),
                          tenant.userId()))
              .isInstanceOf(NotFoundException.class);

          // Inside, it works.
          assertThat(
                  locations
                      .create(
                          new LocationService.CreateLocationCommand(
                              null, categoryOf(tenant), places.garage(), "A shelf", null), Optional.empty(),
                          tenant.userId())
                      .id())
              .isNotNull();
        });
  }

  @Test
  @DisplayName("sees nothing at all when its scope names a place that is gone")
  void anUnresolvableScopeShowsNothing() {
    Tenant tenant = newTenant("scope-gone@example.org");
    Places places = aHouseWithAGarage(tenant);

    // A scope pointing at a place this tenant does not have. The empty string
    // would mean "no scope" and would fail open; ADR-0059 makes it fail closed.
    scoped(
        tenant,
        UUID.randomUUID(),
        () -> {
          assertThat(locations.list(null, 50).data()).isEmpty();
          assertThat(search.query(new SearchService.SearchRequest("", "en", null, null, null, List.of(), List.of(), 50)).data()).isEmpty();
        });

    // And the tenant itself is untouched: the scope was the session's, not the
    // data's.
    unscoped(tenant, () -> assertThat(locations.list(null, 50).data()).hasSize(3));
    assertThat(places.garage()).isNotNull();
  }

  // -------------------------------------------------------------------------

  /** The places and items one test builds. */
  private record Places(UUID house, UUID garage, UUID bedroom, UUID inGarage, UUID inBedroom) {}

  /** The identifiers a test needs. */
  private record Tenant(UUID userId, UUID tenantId) {}

  /**
   * A house with a garage and a bedroom, and one item in each of the two.
   *
   * @param tenant whose inventory
   * @return what was created
   */
  private Places aHouseWithAGarage(Tenant tenant) {
    java.util.concurrent.atomic.AtomicReference<Places> built =
        new java.util.concurrent.atomic.AtomicReference<>();
    unscoped(
        tenant,
        () -> {
          UUID category = categoryOf(tenant);
          UUID house =
              locations
                  .create(
                      new LocationService.CreateLocationCommand(null, category, null, "House", null), Optional.empty(),
                      tenant.userId())
                  .id();
          UUID garage =
              locations
                  .create(
                      new LocationService.CreateLocationCommand(null, category, house, "Garage", null), Optional.empty(),
                      tenant.userId())
                  .id();
          UUID bedroom =
              locations
                  .create(
                      new LocationService.CreateLocationCommand(null, category, house, "Bedroom", null), Optional.empty(),
                      tenant.userId())
                  .id();
          built.set(
              new Places(
                  house,
                  garage,
                  bedroom,
                  anItem(tenant, "A drill", garage),
                  anItem(tenant, "A lamp", bedroom)));
        });
    return built.get();
  }

  /**
   * The tenant's built-in "room" category, which every place here uses.
   *
   * <p>A place needs one — `locations.location.category_version_id` is `NOT NULL` — and which one
   * is beside the point for a test about scopes.
   *
   * @param tenant whose catalogue
   * @return the category's id
   */
  private UUID categoryOf(Tenant tenant) {
    // In a transaction, because `app.tenant_id` is published when one begins: a
    // read outside one runs with no tenant set and the policy returns nothing.
    return transactions.execute(
        status ->
            jdbc
                .sql("select id from catalog.location_category"
                    + " where tenant_id = ? and key = 'room'")
                .param(tenant.tenantId())
                .query(UUID.class)
                .single());
  }

  /**
   * An item in a place.
   *
   * @param tenant whose inventory
   * @param name what to call it
   * @param locationId where it is
   * @return the item's id
   */
  private UUID anItem(Tenant tenant, String name, UUID locationId) {
    return items
        .create(
            new ItemService.CreateItemCommand(
                null,
                null,
                name,
                null,
                ItemKind.PHYSICAL,
                locationId,
                BigDecimal.ONE,
                null,
                null,
                null,
                null, Valuation.NONE), Optional.empty(),
            tenant.userId())
        .item()
        .id();
  }

  @Test
  @DisplayName("holds no whole-tenant permission, because an archive of a shelf does not exist")
  void aScopedMembershipMayNotExportTheTenant() {
    Tenant tenant = newTenant("scope-export@example.org");
    Places places = aHouseWithAGarage(tenant);

    // Over the whole tenant, an owner may ask for an archive of it.
    unscoped(tenant, () -> assertThat(access.holds(Permission.TENANT_EXPORT)).isTrue());

    // The same person confined to the garage may not -- and it is the scope that
    // decides rather than the role, so being an OWNER does not change it. There
    // is no archive of a subtree, so the only thing this caller could be handed
    // is an archive of everything, which is precisely what the scope says they
    // may not have (ADR-0068, open point O27).
    TenantContext.runAs(
        tenant.tenantId(),
        () ->
            CallerContext.runAs(
                new CallerContext.Caller(
                    tenant.userId(), tenant.tenantId(), "OWNER", null, places.garage()),
                () -> assertThat(access.holds(Permission.TENANT_EXPORT)).isFalse()));

    // And only the permissions that say they are whole-tenant are affected: the
    // scoped caller still reads items, which is the entire point of a scope.
    scoped(tenant, places.garage(), () -> assertThat(access.holds(Permission.ITEM_READ)).isTrue());
  }

  /**
   * Runs an action as somebody confined to one place.
   *
   * @param tenant whose inventory
   * @param scopeLocationId the place they are confined to
   * @param action what to run
   */
  private void scoped(Tenant tenant, UUID scopeLocationId, Runnable action) {
    TenantContext.runAs(
        tenant.tenantId(),
        () ->
            CallerContext.runAs(
                new CallerContext.Caller(
                    tenant.userId(), tenant.tenantId(), "MEMBER", null, scopeLocationId),
                action));
  }

  /**
   * Runs an action as somebody with the whole tenant.
   *
   * @param tenant whose inventory
   * @param action what to run
   */
  private void unscoped(Tenant tenant, Runnable action) {
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
}
