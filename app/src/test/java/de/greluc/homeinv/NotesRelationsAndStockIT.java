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
import de.greluc.homeinv.inventory.api.ItemRelations;
import de.greluc.homeinv.inventory.api.ItemService;
import de.greluc.homeinv.inventory.api.ItemView;
import de.greluc.homeinv.inventory.domain.Notes;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Notes, relations and the restocking level (REQ-CORE-014, REQ-CORE-006, REQ-CORE-008).
 *
 * <p>Three small additions to an item, tested together because they are one commit's worth of
 * behaviour and each is a few assertions.
 */
@DisplayName("An item's notes, relations and stock")
class NotesRelationsAndStockIT extends AbstractIntegrationTest {

  @Autowired private ItemService items;
  @Autowired private ItemRelations relations;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private AppUserRepository users;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;

  @Nested
  @DisplayName("notes")
  class NotesTests {

    @Test
    @DisplayName("keep the words and lose the HTML (REQ-CORE-014)")
    void htmlIsStripped() {
      Tenant tenant = newTenant("notes-html@example.org");
      TenantContext.runAs(
          tenant.tenantId(),
          () -> {
            ItemView stored =
                items.create(
                        command(
                            "Toolbox",
                            "Bought at the <b>market</b>. <script>alert('x')</script> Second shelf.",
                            null),
                        tenant.userId())
                    .item();
            assertThat(stored.notes())
                .contains("Bought at the market.")
                .contains("Second shelf.")
                .doesNotContain("<b>")
                .doesNotContain("<script>")
                // The tag goes and the text inside it stays: a person wrote the
                // word, whatever they wrapped it in.
                .contains("market");
            // What a client would have rendered is now what is stored.
            assertThat(stored.notes()).doesNotContain("alert");
          });
    }

    @Test
    @DisplayName("are Markdown otherwise, and are left alone")
    void markdownSurvives() {
      assertThat(Notes.sanitise("# Heading\n\n- one\n- two\n\n**bold** and `code`"))
          .isEqualTo("# Heading\n\n- one\n- two\n\n**bold** and `code`");
      assertThat(Notes.sanitise("   ")).isNull();
      assertThat(Notes.sanitise("<!-- hidden -->visible")).isEqualTo("visible");
    }
  }

  @Nested
  @DisplayName("relations")
  class RelationTests {

    @Test
    @DisplayName("are visible from both ends, and never point at themselves (REQ-CORE-006)")
    void bothEnds() {
      Tenant tenant = newTenant("relations-ends@example.org");
      TenantContext.runAs(
          tenant.tenantId(),
          () -> {
            UUID camera = anItem(tenant, "Camera");
            UUID lens = anItem(tenant, "Lens");

            ItemRelations.RelationView relation =
                relations.relate(
                    lens, camera, ItemRelations.RelationType.ACCESSORY_OF, tenant.userId());
            assertThat(relation.inbound()).isFalse();

            // The lens states it; the camera reads the same row the other way.
            assertThat(relations.relationsOf(lens, null, 50).items())
                .singleElement()
                .satisfies(view -> assertThat(view.inbound()).isFalse());
            assertThat(relations.relationsOf(camera, null, 50).items())
                .singleElement()
                .satisfies(view -> assertThat(view.inbound()).isTrue());

            // Asked for twice, the second is the first.
            relations.relate(lens, camera, ItemRelations.RelationType.ACCESSORY_OF, tenant.userId());
            assertThat(relations.relationsOf(lens, null, 50).items()).hasSize(1);

            assertThatThrownBy(
                    () ->
                        relations.relate(
                            lens, lens, ItemRelations.RelationType.PART_OF, tenant.userId()))
                .isInstanceOf(IllegalArgumentException.class);

            relations.unrelate(relation.id(), tenant.userId());
            assertThat(relations.relationsOf(camera, null, 50).items()).isEmpty();
          });
    }
  }

  @Nested
  @DisplayName("the restocking level")
  class StockTests {

    @Test
    @DisplayName("is carried, and a quantity under it is visible on the item (REQ-CORE-008)")
    void belowMinimum() {
      Tenant tenant = newTenant("stock-minimum@example.org");
      TenantContext.runAs(
          tenant.tenantId(),
          () -> {
            ItemView stored =
                items.create(
                        new ItemService.CreateItemCommand(
                            null,
                            null,
                            "Printer paper",
                            null,
                            ItemKind.DIGITAL,
                            null,
                            BigDecimal.valueOf(5),
                            "packs",
                            null,
                            null,
                            BigDecimal.valueOf(2)),
                        tenant.userId())
                    .item();
            assertThat(stored.minimumStock()).isEqualByComparingTo("2");

            ItemView low =
                items.update(
                    stored.id(),
                    new ItemService.UpdateItemCommand(
                        "Printer paper",
                        null,
                        null,
                        BigDecimal.ONE,
                        "packs",
                        null,
                        null,
                        BigDecimal.valueOf(2)),
                    tenant.userId());
            assertThat(low.quantity()).isEqualByComparingTo("1");
            assertThat(low.minimumStock()).isEqualByComparingTo("2");
          });
    }
  }

  /**
   * A creation command with notes.
   *
   * @param name what to call it
   * @param notes what to write about it
   * @param minimumStock the restocking level, or {@code null}
   * @return the command
   */
  private ItemService.CreateItemCommand command(String name, String notes, BigDecimal minimumStock) {
    return new ItemService.CreateItemCommand(
        null, null, name, null, ItemKind.DIGITAL, null, BigDecimal.ONE, null, null, notes, minimumStock);
  }

  /**
   * A digital item, which needs no place.
   *
   * @param tenant whose inventory
   * @param name what to call it
   * @return the item's id
   */
  private UUID anItem(Tenant tenant, String name) {
    return items.create(command(name, null, null), tenant.userId()).item().id();
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
