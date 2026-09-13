/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.homeinv.authorization.api.Permission;
import de.greluc.homeinv.authorization.api.Role;
import de.greluc.homeinv.authorization.api.RoleAdministration;
import de.greluc.homeinv.catalog.api.LocationCategories;
import de.greluc.homeinv.catalog.api.TypeAdministration;
import de.greluc.homeinv.catalog.api.TypeKind;
import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.inventory.api.ItemKind;
import de.greluc.homeinv.inventory.api.ItemRelations;
import de.greluc.homeinv.inventory.api.ItemService;
import de.greluc.homeinv.locations.api.LocationService;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.tagging.api.TagGroupView;
import de.greluc.homeinv.tagging.api.TagService;
import de.greluc.homeinv.tagging.api.TagView;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Walks every keyset listing past its first page (REQ-NFR-010).
 *
 * <h2>Why this exists as a test of its own</h2>
 *
 * <p>Every collection in this API is capped at 200 a page and resumed with an opaque cursor, and
 * for months every listing built on raw SQL was <b>broken on its second page</b>: the cursor's
 * timestamp was handed to the driver as a {@code java.time.Instant}, which has no SQL type it can
 * infer, and the answer was "bad SQL grammar" for a statement that is perfectly good. Nothing
 * caught it, because a test that asks for one page never asks for the next — and thirteen shipped
 * categories, one built-in type and a handful of tags all fit on one.
 *
 * <p>So the assertion here is deliberately shallow and deliberately wide: create more rows than fit
 * on a page, then follow the cursor to the end and check that nothing was lost, repeated or
 * refused. One listing per test would have been prettier; one test per listing is what makes the
 * next unbounded adapter fail here rather than in front of a user with 201 tags.
 */
@DisplayName("Every listing")
class KeysetPaginationIT extends AbstractIntegrationTest {

  @Autowired private TypeAdministration types;
  @Autowired private LocationCategories categories;
  @Autowired private TagService tags;
  @Autowired private RoleAdministration roles;
  @Autowired private ItemRelations relations;
  @Autowired private ItemService items;
  @Autowired private LocationService locations;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private AppUserRepository users;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private JdbcClient jdbc;

  @Test
  @DisplayName("of item types resumes from its cursor")
  void itemTypes() {
    Tenant tenant = newTenant("paging-types@example.org");
    for (int index = 0; index < 4; index++) {
      String key = "type-" + index;
      inOwn(
          tenant,
          () ->
              types.createItemType(
                  new TypeAdministration.CreateItemTypeCommand(key, TypeKind.PHYSICAL, null, null),
                  tenant.userId()));
    }
    // Four created plus the built-in `general` one every tenant is provisioned
    // with, so three pages of two.
    assertEveryRowIsSeenExactlyOnce(
        tenant,
        5,
        (cursor, size) -> {
          TypeAdministration.ItemTypePage page = types.itemTypes(cursor, size);
          return new Page(
              page.items().stream().map(TypeAdministration.ItemTypeView::id).toList(),
              page.nextCursor());
        });
  }

  @Test
  @DisplayName("of location categories resumes from its cursor, in both ports that offer it")
  void locationCategories() {
    Tenant tenant = newTenant("paging-categories@example.org");

    // The thirteen REQ-CORE-042 ships, through the editor...
    assertEveryRowIsSeenExactlyOnce(
        tenant,
        13,
        (cursor, size) -> {
          TypeAdministration.CategoryPage page = types.categories(cursor, size);
          return new Page(
              page.items().stream().map(TypeAdministration.CategoryView::id).toList(),
              page.nextCursor());
        });

    // ...and through the picker a client builds a location with, which is a
    // different query over the same rows.
    assertEveryRowIsSeenExactlyOnce(
        tenant,
        13,
        (cursor, size) -> {
          LocationCategories.LocationCategoryPage page = categories.list(cursor, size);
          return new Page(
              page.items().stream()
                  .map(de.greluc.homeinv.catalog.api.LocationCategoryView::id)
                  .toList(),
              page.nextCursor());
        });
  }

  @Test
  @DisplayName("of value lists resumes from its cursor")
  void valueLists() {
    Tenant tenant = newTenant("paging-value-lists@example.org");
    for (int index = 0; index < 5; index++) {
      String key = "list-" + index;
      inOwn(
          tenant,
          () ->
              types.createValueList(
                  new TypeAdministration.CreateValueListCommand(key, Map.of("en", key)),
                  tenant.userId()));
    }
    assertEveryRowIsSeenExactlyOnce(
        tenant,
        5,
        (cursor, size) -> {
          TypeAdministration.ValueListPage page = types.valueLists(cursor, size);
          return new Page(
              page.items().stream().map(TypeAdministration.ValueListView::id).toList(),
              page.nextCursor());
        });
  }

  @Test
  @DisplayName("of tags, tag groups and a thing's tags resumes from its cursor")
  void tagListings() {
    Tenant tenant = newTenant("paging-tags@example.org");
    UUID shelf = inOwn(tenant, () -> location(tenant, "Shelf"));
    UUID thing = inOwn(tenant, () -> item(tenant, "Thing", shelf));

    List<UUID> created = new ArrayList<>();
    for (int index = 0; index < 5; index++) {
      String name = "tag-" + index;
      created.add(
          inOwn(
              tenant,
              () ->
                  tags.create(new TagService.CreateTagCommand(name, null, null, null),
                          tenant.userId())
                      .id()));
    }
    for (UUID tag : created) {
      inOwn(
          tenant,
          () -> {
            tags.assign(tag, TagService.TagTarget.ITEM, thing, tenant.userId());
            return null;
          });
    }
    for (int index = 0; index < 3; index++) {
      String key = "group-" + index;
      int order = index;
      inOwn(
          tenant,
          () ->
              tags.createGroup(
                  new TagService.CreateTagGroupCommand(key, Map.of("en", key), false, order),
                  tenant.userId()));
    }

    assertEveryRowIsSeenExactlyOnce(
        tenant,
        5,
        (cursor, size) -> {
          TagService.TagPage page = tags.tags(cursor, size);
          return new Page(
              page.items().stream().map(TagView::id).toList(), page.nextCursor());
        });

    assertEveryRowIsSeenExactlyOnce(
        tenant,
        3,
        (cursor, size) -> {
          TagService.TagGroupPage page = tags.groups(cursor, size);
          return new Page(
              page.items().stream().map(TagGroupView::id).toList(), page.nextCursor());
        });

    assertEveryRowIsSeenExactlyOnce(
        tenant,
        5,
        (cursor, size) -> {
          TagService.TagPage page =
              tags.tagsOf(TagService.TagTarget.ITEM, thing, cursor, size);
          return new Page(
              page.items().stream().map(TagView::id).toList(), page.nextCursor());
        });
  }

  @Test
  @DisplayName("of tenant-owned roles resumes from its cursor")
  void roleDefinitions() {
    Tenant tenant = newTenant("paging-roles@example.org");
    for (int index = 0; index < 5; index++) {
      String name = "Role " + index;
      inOwn(
          tenant,
          () -> roles.create(name, null, Role.VIEWER, Set.<Permission>of(), tenant.userId()));
    }
    assertEveryRowIsSeenExactlyOnce(
        tenant,
        5,
        (cursor, size) -> {
          RoleAdministration.RolePage page = roles.roles(cursor, size);
          return new Page(
              page.items().stream()
                  .map(RoleAdministration.RoleDefinitionView::id)
                  .toList(),
              page.nextCursor());
        });
  }

  @Test
  @DisplayName("of an item's relations resumes from its cursor")
  void itemRelations() {
    Tenant tenant = newTenant("paging-relations@example.org");
    UUID shelf = inOwn(tenant, () -> location(tenant, "Shelf"));
    UUID camera = inOwn(tenant, () -> item(tenant, "Camera", shelf));

    for (int index = 0; index < 5; index++) {
      String name = "Accessory " + index;
      inOwn(
          tenant,
          () -> {
            UUID other = item(tenant, name, shelf);
            relations.relate(
                other, camera, ItemRelations.RelationType.ACCESSORY_OF, tenant.userId());
            return null;
          });
    }
    assertEveryRowIsSeenExactlyOnce(
        tenant,
        5,
        (cursor, size) -> {
          ItemRelations.RelationPage page = relations.relationsOf(camera, cursor, size);
          return new Page(
              page.items().stream().map(ItemRelations.RelationView::id).toList(),
              page.nextCursor());
        });
  }

  // -------------------------------------------------------------------------

  /**
   * Follows a listing's cursor to the end and checks that it gave every row exactly once.
   *
   * <p>Two at a time, so that a listing of five takes three requests and the second one is the page
   * that used to fail. A guard at fifty iterations turns "the cursor never advances" into a failed
   * assertion rather than a test that hangs.
   *
   * @param tenant whose context to read in
   * @param expected how many rows the listing holds
   * @param listing takes a cursor and a size, answers the ids and the next cursor
   */
  private void assertEveryRowIsSeenExactlyOnce(
      Tenant tenant, int expected, BiFunction<String, Integer, Page> listing) {

    List<UUID> seen = new ArrayList<>();
    String cursor = null;
    int pages = 0;
    do {
      String current = cursor;
      Page page = inOwn(tenant, () -> listing.apply(current, 2));
      seen.addAll(page.ids());
      cursor = page.nextCursor();
      pages++;
      assertThat(pages).as("the cursor stopped advancing").isLessThan(50);
    } while (cursor != null);

    assertThat(seen).as("a row was repeated across pages").doesNotHaveDuplicates();
    assertThat(seen).as("a row was skipped between pages").hasSize(expected);
  }

  /**
   * One page as this test reads it.
   *
   * @param ids the ids on the page, in the order the listing gave them
   * @param nextCursor the cursor for the next page, or {@code null}
   */
  private record Page(List<UUID> ids, String nextCursor) {}

  private UUID location(Tenant tenant, String name) {
    return locations
        .create(
            new LocationService.CreateLocationCommand(
                null, categoryNamed(tenant.tenantId(), "room"), null, name, null),
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
                null),
            tenant.userId())
        .item()
        .id();
  }

  private UUID categoryNamed(UUID tenantId, String key) {
    return jdbc
        .sql("select id from catalog.location_category where tenant_id = ? and key = ?")
        .params(tenantId, key)
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
                    "Pager",
                    "en",
                    passwordEncoder.encode("irrelevant"),
                    Instant.now())));
    return new Tenant(userId, provisioning.provision("Tenant of " + email, userId));
  }

  private record Tenant(UUID userId, UUID tenantId) {}
}
