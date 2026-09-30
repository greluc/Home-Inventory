/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import de.greluc.homeinv.platform.Page;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.inventory.api.BundleCycleException;
import de.greluc.homeinv.inventory.api.ItemBundles;
import de.greluc.homeinv.inventory.api.ItemKind;
import de.greluc.homeinv.inventory.api.ItemService;
import de.greluc.homeinv.inventory.api.Valuation;
import de.greluc.homeinv.inventory.api.ItemView;
import de.greluc.homeinv.locations.api.LocationService;
import de.greluc.homeinv.platform.NotFoundException;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves an item can contain other items without owning where they are (REQ-CORE-007).
 *
 * <p>Two properties, and the second is the one that needs a test rather than a reading. A bundle is
 * not a place, so putting something into one must leave its location exactly as it was. And the
 * containment graph must stay acyclic while an item may be in <em>several</em> bundles — which makes
 * a cycle a reachability question, so the interesting case is the long one: A contains B contains C,
 * and C is then offered A.
 */
@DisplayName("A bundle")
class ItemBundleIT extends AbstractIntegrationTest {

  @Autowired private ItemBundles bundles;
  @Autowired private ItemService items;
  @Autowired private LocationService locations;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private AppUserRepository users;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private JdbcClient jdbc;

  @Test
  @DisplayName("contains things without taking them out of the place they are kept")
  void aMemberKeepsItsLocation() {
    Tenant tenant = newTenant("bundle-location@example.org");
    UUID shelf = inOwn(tenant, () -> location(tenant, "Shelf"));
    UUID drawer = inOwn(tenant, () -> location(tenant, "Drawer"));

    UUID bag = inOwn(tenant, () -> item(tenant, "Camera bag", shelf));
    UUID lens = inOwn(tenant, () -> item(tenant, "Lens", drawer));

    ItemBundles.BundleMemberView membership =
        inOwn(tenant, () -> bundles.add(bag, lens, tenant.userId()));
    assertThat(membership.bundleId()).isEqualTo(bag);
    assertThat(membership.memberId()).isEqualTo(lens);

    assertThat(inOwn(tenant, () -> items.get(lens).locationId())).isEqualTo(drawer);
    assertThat(inOwn(tenant, () -> items.get(bag).locationId())).isEqualTo(shelf);

    assertThat(inOwn(tenant, () -> bundles.contentsOf(bag, null, 50).data()))
        .extracting(ItemBundles.BundleMemberView::memberId)
        .containsExactly(lens);
    assertThat(inOwn(tenant, () -> bundles.bundlesOf(lens, null, 50).data()))
        .extracting(ItemBundles.BundleMemberView::bundleId)
        .containsExactly(bag);
  }

  @Test
  @DisplayName("holds an item that is in another bundle at the same time")
  void anItemIsInSeveralBundles() {
    Tenant tenant = newTenant("bundle-several@example.org");
    UUID shelf = inOwn(tenant, () -> location(tenant, "Shelf"));

    UUID bag = inOwn(tenant, () -> item(tenant, "Camera bag", shelf));
    UUID insured = inOwn(tenant, () -> item(tenant, "Insured equipment", shelf));
    UUID lens = inOwn(tenant, () -> item(tenant, "Lens", shelf));

    inOwn(tenant, () -> bundles.add(bag, lens, tenant.userId()));
    inOwn(tenant, () -> bundles.add(insured, lens, tenant.userId()));

    assertThat(inOwn(tenant, () -> bundles.bundlesOf(lens, null, 50).data()))
        .extracting(ItemBundles.BundleMemberView::bundleId)
        .containsExactlyInAnyOrder(bag, insured);
  }

  @Test
  @DisplayName("is asked for twice and made once")
  void addingTwiceIsAddingOnce() {
    Tenant tenant = newTenant("bundle-idempotent@example.org");
    UUID shelf = inOwn(tenant, () -> location(tenant, "Shelf"));
    UUID box = inOwn(tenant, () -> item(tenant, "Box", shelf));
    UUID cable = inOwn(tenant, () -> item(tenant, "Cable", shelf));

    ItemBundles.BundleMemberView first =
        inOwn(tenant, () -> bundles.add(box, cable, tenant.userId()));
    ItemBundles.BundleMemberView again =
        inOwn(tenant, () -> bundles.add(box, cable, tenant.userId()));

    assertThat(again.id()).isEqualTo(first.id());
    assertThat(inOwn(tenant, () -> bundles.contentsOf(box, null, 50).data())).hasSize(1);

    inOwn(
        tenant,
        () -> {
          bundles.remove(box, cable, tenant.userId());
          bundles.remove(box, cable, tenant.userId());
          return null;
        });
    assertThat(inOwn(tenant, () -> bundles.contentsOf(box, null, 50).data())).isEmpty();
    assertThat(inOwn(tenant, () -> items.get(cable).locationId())).isEqualTo(shelf);
  }

  @Test
  @DisplayName("cannot end up inside itself, however long the chain")
  void aCycleIsRefused() {
    Tenant tenant = newTenant("bundle-cycle@example.org");
    UUID shelf = inOwn(tenant, () -> location(tenant, "Shelf"));

    UUID outer = inOwn(tenant, () -> item(tenant, "Crate", shelf));
    UUID middle = inOwn(tenant, () -> item(tenant, "Case", shelf));
    UUID inner = inOwn(tenant, () -> item(tenant, "Pouch", shelf));

    inOwn(tenant, () -> bundles.add(outer, middle, tenant.userId()));
    inOwn(tenant, () -> bundles.add(middle, inner, tenant.userId()));

    assertThatThrownBy(() -> inOwn(tenant, () -> bundles.add(outer, outer, tenant.userId())))
        .isInstanceOf(BundleCycleException.class);

    assertThatThrownBy(() -> inOwn(tenant, () -> bundles.add(middle, outer, tenant.userId())))
        .isInstanceOf(BundleCycleException.class);

    assertThatThrownBy(() -> inOwn(tenant, () -> bundles.add(inner, outer, tenant.userId())))
        .isInstanceOf(BundleCycleException.class)
        .hasMessageContaining("contain");

    assertThat(inOwn(tenant, () -> bundles.contentsOf(inner, null, 50).data())).isEmpty();
    assertThat(inOwn(tenant, () -> bundles.contentsOf(middle, null, 50).data())).hasSize(1);

    inOwn(tenant, () -> bundles.add(outer, inner, tenant.userId()));
    assertThat(inOwn(tenant, () -> bundles.contentsOf(outer, null, 50).data()))
        .extracting(ItemBundles.BundleMemberView::memberId)
        .containsExactlyInAnyOrder(middle, inner);
  }

  @Test
  @DisplayName("leaves out what is in the trash, and refuses to take something that is not there")
  void trashedMembersAndUnknownItems() {
    Tenant tenant = newTenant("bundle-trash@example.org");
    UUID shelf = inOwn(tenant, () -> location(tenant, "Shelf"));
    UUID kit = inOwn(tenant, () -> item(tenant, "Kit", shelf));
    UUID spanner = inOwn(tenant, () -> item(tenant, "Spanner", shelf));

    inOwn(tenant, () -> bundles.add(kit, spanner, tenant.userId()));
    inOwn(
        tenant,
        () -> {
          items.delete(spanner, OptionalLong.empty(), tenant.userId());
          return null;
        });

    assertThat(inOwn(tenant, () -> bundles.contentsOf(kit, null, 50).data())).isEmpty();
    assertThat(
            inOwn(
                tenant,
                () ->
                    jdbc.sql(
                            "select count(*) from inventory.item_bundle where bundle_item_id = ?")
                        .param(kit)
                        .query(Integer.class)
                        .single()))
        .isEqualTo(1);

    inOwn(tenant, () -> items.restore(spanner, OptionalLong.empty(), tenant.userId()));
    assertThat(inOwn(tenant, () -> bundles.contentsOf(kit, null, 50).data())).hasSize(1);

    UUID stranger = UUID.randomUUID();
    assertThatThrownBy(() -> inOwn(tenant, () -> bundles.add(kit, stranger, tenant.userId())))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  @DisplayName("pages its contents, and the pages do not overlap (REQ-NFR-010)")
  void theContentsArePaged() {
    Tenant tenant = newTenant("bundle-paged@example.org");
    UUID shelf = inOwn(tenant, () -> location(tenant, "Shelf"));
    UUID chest = inOwn(tenant, () -> item(tenant, "Chest", shelf));

    for (int index = 0; index < 5; index++) {
      String name = "Thing " + index;
      inOwn(
          tenant,
          () -> {
            bundles.add(chest, item(tenant, name, shelf), tenant.userId());
            return null;
          });
    }

    Page<ItemBundles.BundleMemberView> first =
        inOwn(tenant, () -> bundles.contentsOf(chest, null, 2));
    Page<ItemBundles.BundleMemberView> second =
        inOwn(tenant, () -> bundles.contentsOf(chest, first.nextCursor(), 2));
    Page<ItemBundles.BundleMemberView> third =
        inOwn(tenant, () -> bundles.contentsOf(chest, second.nextCursor(), 2));

    assertThat(first.data()).hasSize(2);
    assertThat(second.data()).hasSize(2);
    assertThat(third.data()).hasSize(1);
    assertThat(third.nextCursor()).isNull();

    List<UUID> seen =
        java.util.stream.Stream.of(first, second, third)
            .flatMap(page -> page.data().stream())
            .map(ItemBundles.BundleMemberView::id)
            .toList();
    assertThat(seen).doesNotHaveDuplicates().hasSize(5);
  }

  private UUID location(Tenant tenant, String name) {
    return locations
        .create(
            new LocationService.CreateLocationCommand(
                null, anyCategory(tenant.tenantId()), null, name, null),
            Optional.empty(),
            tenant.userId())
        .id();
  }

  private UUID item(Tenant tenant, String name, UUID locationId) {
    return items
        .create(
            new ItemService.CreateItemCommand(
                null,
                builtinType(tenant.tenantId()),
                name,
                null,
                ItemKind.PHYSICAL,
                locationId,
                BigDecimal.ONE,
                null,
                "{}",
                null,
                null, Valuation.NONE),
            Optional.empty(),
            tenant.userId())
        .item()
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

  /**
   * Runs a body in the tenant's context and in a transaction of its own.
   *
   * @param tenant the tenant
   * @param body the work
   * @param <T> what it produces
   * @return the result
   */
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
                    "Bundler",
                    "en",
                    passwordEncoder.encode("irrelevant"),
                    Instant.now())));
    return new Tenant(userId, provisioning.provision("Tenant of " + email, userId));
  }

  private record Tenant(UUID userId, UUID tenantId) {}
}
