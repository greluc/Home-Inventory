/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.homeinv.catalog.api.LocationCategories;
import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.inventory.api.ItemKind;
import de.greluc.homeinv.inventory.api.ItemService;
import de.greluc.homeinv.locations.api.LocationService;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.tagging.api.TagService;
import de.greluc.homeinv.tagging.api.TagView;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tags across items and places (REQ-CORE-060…063).
 *
 * <p>Through the services, because what is under test is the block's behaviour: one vocabulary for
 * two kinds of thing, exclusivity inside a group, and a merge that moves assignments without
 * producing duplicates. The REST layer over it is an adapter.
 */
@DisplayName("Tags")
class TagsIT extends AbstractIntegrationTest {

  @Autowired private TagService tags;
  @Autowired private ItemService items;
  @Autowired private LocationService locations;
  @Autowired private LocationCategories categories;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private AppUserRepository users;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;

  @Test
  @DisplayName("are tenant-wide and go on items and places alike (REQ-CORE-060)")
  void oneVocabularyForBoth() {
    Tenant tenant = newTenant("tags-both@example.org");
    TenantContext.runAs(
        tenant.tenantId(),
        () -> {
          TagView fragile =
              tags.create(
                  new TagService.CreateTagCommand("Fragile", null, "#ff0000", null),
                  tenant.userId());
          UUID item = anItem(tenant, "Vase");
          UUID place = aPlace(tenant, "Cellar");

          tags.assign(fragile.id(), TagService.TagTarget.ITEM, item, tenant.userId());
          tags.assign(fragile.id(), TagService.TagTarget.LOCATION, place, tenant.userId());

          assertThat(tagsOn(TagService.TagTarget.ITEM, item))
              .extracting(TagView::name)
              .containsExactly("Fragile");
          assertThat(tagsOn(TagService.TagTarget.LOCATION, place))
              .extracting(TagView::name)
              .containsExactly("Fragile");

          // Assigning twice is not an error: a client retrying a request it never
          // saw the answer to must not be told it failed.
          tags.assign(fragile.id(), TagService.TagTarget.ITEM, item, tenant.userId());
          assertThat(tagsOn(TagService.TagTarget.ITEM, item)).hasSize(1);

          tags.unassign(fragile.id(), TagService.TagTarget.ITEM, item, tenant.userId());
          assertThat(tagsOn(TagService.TagTarget.ITEM, item)).isEmpty();
          // Taking it off one thing leaves it on the other.
          assertThat(tagsOn(TagService.TagTarget.LOCATION, place)).hasSize(1);
        });
  }

  @Test
  @DisplayName("refuse a second tag with the same name, whatever its case")
  void namesAreUnique() {
    Tenant tenant = newTenant("tags-unique@example.org");
    TenantContext.runAs(
        tenant.tenantId(),
        () -> {
          tags.create(new TagService.CreateTagCommand("Fragile", null, null, null), tenant.userId());
          assertThatThrownBy(
                  () ->
                      tags.create(
                          new TagService.CreateTagCommand("fragile", null, null, null),
                          tenant.userId()))
              .isInstanceOf(TagService.TagNameTakenException.class);
        });
  }

  @Test
  @DisplayName("hold one tag per thing inside an exclusive group (REQ-CORE-061)")
  void exclusiveGroups() {
    Tenant tenant = newTenant("tags-exclusive@example.org");
    TenantContext.runAs(
        tenant.tenantId(),
        () -> {
          UUID condition =
              tags.createGroup(
                      new TagService.CreateTagGroupCommand(
                          "condition", Map.of("en", "Condition"), true, 0),
                      tenant.userId())
                  .id();
          TagView fresh =
              tags.create(
                  new TagService.CreateTagCommand("New", condition, null, null), tenant.userId());
          TagView used =
              tags.create(
                  new TagService.CreateTagCommand("Used", condition, null, null), tenant.userId());
          UUID item = anItem(tenant, "Drill");

          tags.assign(fresh.id(), TagService.TagTarget.ITEM, item, tenant.userId());
          tags.assign(used.id(), TagService.TagTarget.ITEM, item, tenant.userId());

          // The second displaces the first: a thing that was both new and used
          // would be a thing whose condition nobody can read.
          assertThat(tagsOn(TagService.TagTarget.ITEM, item))
              .extracting(TagView::name)
              .containsExactly("Used");
        });
  }

  @Test
  @DisplayName("move every assignment when two are merged, and drop the duplicates (REQ-CORE-063)")
  void mergeMovesAssignments() {
    Tenant tenant = newTenant("tags-merge@example.org");
    TenantContext.runAs(
        tenant.tenantId(),
        () -> {
          TagView loose =
              tags.create(
                  new TagService.CreateTagCommand("Breakable", null, null, null), tenant.userId());
          TagView keep =
              tags.create(
                  new TagService.CreateTagCommand("Fragile", null, null, null), tenant.userId());

          UUID both = anItem(tenant, "Mirror");
          UUID onlyLoose = anItem(tenant, "Glass");
          tags.assign(loose.id(), TagService.TagTarget.ITEM, both, tenant.userId());
          tags.assign(keep.id(), TagService.TagTarget.ITEM, both, tenant.userId());
          tags.assign(loose.id(), TagService.TagTarget.ITEM, onlyLoose, tenant.userId());

          tags.merge(loose.id(), keep.id(), tenant.userId());

          // The thing that had both keeps one; the thing that had only the source
          // now carries the target.
          assertThat(tagsOn(TagService.TagTarget.ITEM, both))
              .extracting(TagView::name)
              .containsExactly("Fragile");
          assertThat(tagsOn(TagService.TagTarget.ITEM, onlyLoose))
              .extracting(TagView::name)
              .containsExactly("Fragile");

          // The source is a tombstone pointing at what it became, so a client
          // holding the old id is redirected rather than told it never existed.
          assertThat(tags.tags(null, 50).items()).extracting(TagView::name).containsExactly("Fragile");

          // And the name it freed can be taken again.
          tags.create(new TagService.CreateTagCommand("Breakable", null, null, null), tenant.userId());
        });
  }

  /**
   * The tags one thing carries, as a list, because a test asserting on a page reads worse.
   *
   * @param target what kind of thing
   * @param targetId the item or place
   * @return the first page of its tags, which for these fixtures is all of them
   */
  private java.util.List<TagView> tagsOn(TagService.TagTarget target, UUID targetId) {
    return tags.tagsOf(target, targetId, null, 50).items();
  }

  /**
   * An item to hang a tag on.
   *
   * @param tenant whose inventory
   * @param name what to call it
   * @return the item's id
   */
  private UUID anItem(Tenant tenant, String name) {
    return items.create(
            new ItemService.CreateItemCommand(
                null, null, name, null, ItemKind.DIGITAL, null, BigDecimal.ONE, null, null, null, null), Optional.empty(),
            tenant.userId())
        .item()
        .id();
  }

  /**
   * A place to hang a tag on.
   *
   * @param tenant whose tree
   * @param name what to call it
   * @return the place's id
   */
  private UUID aPlace(Tenant tenant, String name) {
    UUID category = categories.list(null, 1).items().getFirst().id();
    return locations
        .create(
            new LocationService.CreateLocationCommand(null, category, null, name, null), Optional.empty(),
            tenant.userId())
        .id();
  }

  /**
   * A provisioned tenant with an owner.
   *
   * @param email the owner's address
   * @return the tenant and its owner
   */
  private Tenant newTenant(String email) {
    UUID userId = UUID.randomUUID();
    transactions.executeWithoutResult(
        status ->
            users.save(
                AppUser.create(
                    userId, email, "Test", "en", passwordEncoder.encode("irrelevant"), Instant.now())));
    return new Tenant(userId, provisioning.provision("Tenant of " + email, userId));
  }

  /**
   * The identifiers a test needs.
   *
   * @param userId the owner
   * @param tenantId the tenant
   */
  private record Tenant(UUID userId, UUID tenantId) {}
}
