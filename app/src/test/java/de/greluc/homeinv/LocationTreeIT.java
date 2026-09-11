/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.locations.api.LocationView;
import de.greluc.homeinv.locations.application.LocationNotEmptyException;
import de.greluc.homeinv.locations.application.LocationService;
import de.greluc.homeinv.locations.domain.Location;
import de.greluc.homeinv.locations.domain.TooDeepException;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves the location tree behaves as the materialised path promises.
 *
 * <p>Driven through the service rather than through HTTP, because what is under test is the tree
 * itself — the {@code ltree} operators, the depth constraint, the refusal to orphan. The REST layer
 * over it is an adapter and is covered where adapters are covered.
 */
@DisplayName("The location tree")
class LocationTreeIT extends AbstractIntegrationTest {

  @Autowired private LocationService locationService;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private AppUserRepository users;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private JdbcClient jdbc;

  @Test
  @DisplayName("returns a full path without recursing, and one query deep")
  void pathIsRetrievableWithoutRecursion() {
    Tenant tenant = newTenant("path@example.org");

    inTenantTransaction(
        tenant.tenantId(),
        () -> {
          UUID category = anyCategory(tenant.tenantId());
          LocationView house = create(category, null, "Haus", tenant.userId());
          LocationView cellar = create(category, house.id(), "Keller", tenant.userId());
          LocationView shelf = create(category, cellar.id(), "Regal 3", tenant.userId());

          // REQ-CORE-044: the names root-first, assembled from the path, no recursion.
          assertThat(locationService.get(shelf.id()).ancestors())
              .containsExactly("Haus", "Keller", "Regal 3");
          assertThat(locationService.get(shelf.id()).depth()).isEqualTo(2);
        });
  }

  @Test
  @DisplayName("lists a whole subtree in one index range")
  void subtreeIsListable() {
    Tenant tenant = newTenant("subtree@example.org");

    inTenantTransaction(
        tenant.tenantId(),
        () -> {
          UUID category = anyCategory(tenant.tenantId());
          LocationView house = create(category, null, "Haus", tenant.userId());
          LocationView cellar = create(category, house.id(), "Keller", tenant.userId());
          LocationView shelf = create(category, cellar.id(), "Regal", tenant.userId());
          LocationView attic = create(category, house.id(), "Dachboden", tenant.userId());

          // REQ-CORE-049, including the subtree: everything below the house.
          assertThat(locationService.subtreeIds(house.id()))
              .containsExactlyInAnyOrder(house.id(), cellar.id(), shelf.id(), attic.id());

          // And a branch of it, which is the same query one level down.
          assertThat(locationService.subtreeIds(cellar.id()))
              .containsExactlyInAnyOrder(cellar.id(), shelf.id());
        });
  }

  @Test
  @DisplayName("refuses to go deeper than twelve levels")
  void depthIsCapped() {
    Tenant tenant = newTenant("depth@example.org");

    UUID category = inOwnTransaction(tenant.tenantId(), () -> anyCategory(tenant.tenantId()));

    UUID deepest =
        inOwnTransaction(
            tenant.tenantId(),
            () -> {
              LocationView current = create(category, null, "Ebene 0", tenant.userId());
              // MAX_DEPTH is the deepest allowed, so a root plus MAX_DEPTH children is legal.
              for (int depth = 1; depth <= Location.MAX_DEPTH; depth++) {
                current = create(category, current.id(), "Ebene " + depth, tenant.userId());
              }
              assertThat(current.depth()).isEqualTo(Location.MAX_DEPTH);
              return current.id();
            });

    assertThatThrownBy(
            () ->
                inOwnTransaction(
                    tenant.tenantId(), () -> create(category, deepest, "zu tief", tenant.userId())))
        .isInstanceOf(TooDeepException.class);
  }

  @Test
  @DisplayName("refuses to delete a location that still contains something")
  void deletionIsRefusedWhileNotEmpty() {
    Tenant tenant = newTenant("delete@example.org");

    UUID category = inOwnTransaction(tenant.tenantId(), () -> anyCategory(tenant.tenantId()));
    UUID houseId =
        inOwnTransaction(
            tenant.tenantId(), () -> create(category, null, "Haus", tenant.userId()).id());
    UUID roomId =
        inOwnTransaction(
            tenant.tenantId(), () -> create(category, houseId, "Zimmer", tenant.userId()).id());

    assertThatThrownBy(
            () ->
                inOwnTransaction(
                    tenant.tenantId(),
                    () -> {
                      locationService.delete(houseId, tenant.userId());
                      return null;
                    }))
        .isInstanceOf(LocationNotEmptyException.class)
        .hasMessageContaining("other locations");

    // The leaf goes, and then the parent can follow.
    inOwnTransaction(
        tenant.tenantId(),
        () -> {
          locationService.delete(roomId, tenant.userId());
          return null;
        });
    inOwnTransaction(
        tenant.tenantId(),
        () -> {
          locationService.delete(houseId, tenant.userId());
          return null;
        });
  }

  @Test
  @DisplayName("keeps the path made of ids, so a rename touches one row")
  void renameDoesNotRewriteThePath() {
    Tenant tenant = newTenant("rename@example.org");

    UUID category = inOwnTransaction(tenant.tenantId(), () -> anyCategory(tenant.tenantId()));
    UUID houseId =
        inOwnTransaction(
            tenant.tenantId(), () -> create(category, null, "Haus", tenant.userId()).id());
    UUID roomId =
        inOwnTransaction(
            tenant.tenantId(), () -> create(category, houseId, "Küche", tenant.userId()).id());

    String pathBefore = inOwnTransaction(tenant.tenantId(), () -> rawPath(roomId));
    inOwnTransaction(
        tenant.tenantId(), () -> locationService.rename(roomId, "Küche (oben)", tenant.userId()));

    // The name is not in the path, so the path cannot have changed - and a name
    // with a space and brackets is not even a legal ltree label.
    assertThat(inOwnTransaction(tenant.tenantId(), () -> rawPath(roomId))).isEqualTo(pathBefore);
    assertThat(inOwnTransaction(tenant.tenantId(), () -> locationService.get(roomId).ancestors()))
        .containsExactly("Haus", "Küche (oben)");
  }

  /**
   * Runs a body inside the tenant context *and* inside a transaction.
   *
   * <p>Both are needed, and the reason is the property under test elsewhere: the tenant reaches the
   * database through {@code SET LOCAL}, which the transaction manager issues when a transaction
   * begins. A query outside a transaction therefore carries no tenant, every policy evaluates
   * against NULL, and the result is empty — correct behaviour that reads like missing data.
   *
   * @param tenantId the tenant to act as
   * @param body the work
   */
  private void inTenantTransaction(UUID tenantId, Runnable body) {
    TenantContext.runAs(tenantId, () -> transactions.executeWithoutResult(status -> body.run()));
  }

  /**
   * Runs a body in its own committed transaction and returns its result.
   *
   * <p>Each step gets a transaction of its own rather than sharing one, for two reasons that show
   * up only in tests. A service method that throws inside an enclosing transaction marks that
   * transaction rollback-only, so an assertion about the exception is followed by a failure at
   * commit that has nothing to do with the test. And a raw SQL query cannot see a JPA insert that
   * has not been flushed, which reads as missing data.
   *
   * @param tenantId the tenant to act as
   * @param body the work
   * @param <T> what the body produces
   * @return the result
   */
  private <T> T inOwnTransaction(UUID tenantId, java.util.function.Supplier<T> body) {
    return TenantContext.callAs(tenantId, () -> transactions.execute(status -> body.get()));
  }

  private LocationView create(UUID categoryId, UUID parentId, String name, UUID actor) {
    return locationService.create(
        new LocationService.CreateLocationCommand(null, categoryId, parentId, name), actor);
  }

  private String rawPath(UUID locationId) {
    return jdbc.sql("select path::text from locations.location where id = ?")
        .param(locationId)
        .query(String.class)
        .single();
  }

  private UUID anyCategory(UUID tenantId) {
    return jdbc
        .sql("select id from catalog.location_category where tenant_id = ? and key = 'room'")
        .param(tenantId)
        .query(UUID.class)
        .single();
  }

  private Tenant newTenant(String email) {
    UUID userId = UUID.randomUUID();
    transactions.executeWithoutResult(
        status ->
            users.save(
                AppUser.create(
                    userId, email, "Test", "de", passwordEncoder.encode("irrelevant"), Instant.now())));
    return new Tenant(userId, provisioning.provision("Tenant of " + email, userId));
  }

  private record Tenant(UUID userId, UUID tenantId) {}
}
