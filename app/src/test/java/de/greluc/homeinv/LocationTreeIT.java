/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.homeinv.catalog.api.LocationCategoryView;
import de.greluc.homeinv.catalog.api.LocationCategories;
import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.locations.api.LocationView;
import de.greluc.homeinv.locations.api.LocationNotEmptyException;
import de.greluc.homeinv.locations.api.NameTakenException;
import de.greluc.homeinv.locations.api.LocationService;
import de.greluc.homeinv.locations.domain.Location;
import de.greluc.homeinv.locations.api.TooDeepException;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
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
  @Autowired private LocationCategories locationCategories;
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
  @DisplayName("refuses a name a live sibling already has, and lets a tombstone's name be re-used")
  void siblingNamesAreUnique() {
    Tenant tenant = newTenant("siblings@example.org");
    UUID category = inOwnTransaction(tenant.tenantId(), () -> anyCategory(tenant.tenantId()));

    UUID cellar =
        inOwnTransaction(
            tenant.tenantId(), () -> create(category, null, "Cellar", tenant.userId()).id());

    // REQ-CORE-064. The partial unique index enforced this from the first
    // migration and nothing answered for it, so this case was a 500 until
    // 2026-09-12 — found by running the smoke journey twice.
    assertThatThrownBy(
            () ->
                inOwnTransaction(
                    tenant.tenantId(), () -> create(category, null, "Cellar", tenant.userId())))
        .isInstanceOf(NameTakenException.class);

    // Case-insensitively, because the index compares `lower(name)` and because a
    // tree with "Cellar" and "cellar" side by side is one nobody can navigate.
    assertThatThrownBy(
            () ->
                inOwnTransaction(
                    tenant.tenantId(), () -> create(category, null, "CELLAR", tenant.userId())))
        .isInstanceOf(NameTakenException.class);

    // A different parent is a different set of siblings.
    UUID house =
        inOwnTransaction(
            tenant.tenantId(), () -> create(category, null, "House", tenant.userId()).id());
    inOwnTransaction(
        tenant.tenantId(), () -> create(category, house, "Cellar", tenant.userId()));

    // Renaming a location to its own name in a different case is a rename, not a
    // conflict with the row being renamed.
    inTenantTransaction(
        tenant.tenantId(), () -> locationService.rename(cellar, "cellar", OptionalLong.empty(), tenant.userId()));

    // And a tombstone does not hold the name: the index is partial for exactly
    // this (07 §7.1, rule 5).
    inTenantTransaction(tenant.tenantId(), () -> locationService.delete(cellar, OptionalLong.empty(), tenant.userId()));
    LocationView reborn =
        inOwnTransaction(
            tenant.tenantId(), () -> create(category, null, "Cellar", tenant.userId()));
    assertThat(reborn.id()).isNotEqualTo(cellar);
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
                      locationService.delete(houseId, OptionalLong.empty(), tenant.userId());
                      return null;
                    }))
        .isInstanceOf(LocationNotEmptyException.class)
        .hasMessageContaining("other locations");

    // The leaf goes, and then the parent can follow.
    inOwnTransaction(
        tenant.tenantId(),
        () -> {
          locationService.delete(roomId, OptionalLong.empty(), tenant.userId());
          return null;
        });
    inOwnTransaction(
        tenant.tenantId(),
        () -> {
          locationService.delete(houseId, OptionalLong.empty(), tenant.userId());
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
        tenant.tenantId(), () -> locationService.rename(roomId, "Küche (oben)", OptionalLong.empty(), tenant.userId()));

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
  @Test
  @DisplayName("is listable, so a client can offer somewhere to put an item")
  void theTreeIsListable() {
    Tenant tenant = newTenant("listing@example.org");
    UUID category = inOwnTransaction(tenant.tenantId(), () -> anyCategory(tenant.tenantId()));

    LocationView building =
        inOwnTransaction(
            tenant.tenantId(), () -> create(category, null, "Building", tenant.userId()));
    LocationView room =
        inOwnTransaction(
            tenant.tenantId(), () -> create(category, building.id(), "Room", tenant.userId()));
    inOwnTransaction(tenant.tenantId(), () -> create(category, room.id(), "Shelf", tenant.userId()));

    LocationService.LocationPage page =
        inOwnTransaction(tenant.tenantId(), () -> locationService.list(null, 50));

    assertThat(page.items()).hasSize(3);
    assertThat(page.nextCursor()).isNull();
    // Each row carries what a tree is assembled from: the parent, and the
    // readable path a picker shows so that two shelves called "Shelf" are
    // distinguishable.
    LocationView shelf = page.items().get(2);
    assertThat(shelf.parentId()).isEqualTo(room.id());
    assertThat(shelf.ancestors()).containsExactly("Building", "Room", "Shelf");
  }

  @Test
  @DisplayName("is paged, and the pages do not overlap (REQ-NFR-010)")
  void theListingIsPaged() {
    Tenant tenant = newTenant("listing-paged@example.org");
    UUID category = inOwnTransaction(tenant.tenantId(), () -> anyCategory(tenant.tenantId()));
    for (int index = 0; index < 5; index++) {
      String name = "Room " + index;
      inOwnTransaction(tenant.tenantId(), () -> create(category, null, name, tenant.userId()));
    }

    LocationService.LocationPage first =
        inOwnTransaction(tenant.tenantId(), () -> locationService.list(null, 2));
    LocationService.LocationPage second =
        inOwnTransaction(tenant.tenantId(), () -> locationService.list(first.nextCursor(), 2));
    LocationService.LocationPage third =
        inOwnTransaction(tenant.tenantId(), () -> locationService.list(second.nextCursor(), 2));

    assertThat(first.items()).hasSize(2);
    assertThat(second.items()).hasSize(2);
    assertThat(third.items()).hasSize(1);
    assertThat(third.nextCursor()).isNull();

    List<UUID> seen = new java.util.ArrayList<>();
    first.items().forEach(view -> seen.add(view.id()));
    second.items().forEach(view -> seen.add(view.id()));
    third.items().forEach(view -> seen.add(view.id()));
    assertThat(seen).doesNotHaveDuplicates().hasSize(5);
  }

  @Test
  @DisplayName("offers the shipped categories, or nothing could be created at all")
  void theShippedCategoriesAreReadable() {
    Tenant tenant = newTenant("categories@example.org");

    LocationCategories.LocationCategoryPage page =
        inOwnTransaction(tenant.tenantId(), () -> locationCategories.list(null, 50));

    // The thirteen REQ-CORE-042 lists, by key. No assertion about their order:
    // they are written in one transaction and share a timestamp, so the keyset
    // tiebreaker decides, and the client sorts thirteen translated words in the
    // reader's language anyway.
    assertThat(page.items().stream().map(LocationCategoryView::key))
        .containsExactlyInAnyOrder(
            "building",
            "floor",
            "room",
            "furniture",
            "shelf",
            "compartment",
            "drawer",
            "box",
            "moving-box",
            "vehicle",
            "warehouse",
            "outdoor-storage",
            "locker");
    // Every shipped category is stationary, as a default rather than as a claim
    // about what a vehicle is. The move of REQ-CORE-043 carries a location's
    // contents whatever its category says, because a tree built wrongly has to be
    // repairable; what the flag decides is what a client OFFERS. Flipping the
    // shipped default would divide tenants in two, because a data migration
    // cannot reach the ones already provisioned -- `homeinv_migrator` is
    // NOBYPASSRLS and the policies are FORCEd -- so it stays a tenant's to set.
    assertThat(page.items()).noneMatch(LocationCategoryView::mobile);
  }

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
        new LocationService.CreateLocationCommand(null, categoryId, parentId, name, null), actor);
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
