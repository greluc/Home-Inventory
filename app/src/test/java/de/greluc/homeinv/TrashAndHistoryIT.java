/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.homeinv.audit.api.RevisionLog;
import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.inventory.api.ItemKind;
import de.greluc.homeinv.inventory.api.ItemService;
import de.greluc.homeinv.inventory.api.ItemView;
import de.greluc.homeinv.platform.NotFoundException;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Two-stage deletion and the revision history (REQ-CORE-009, REQ-CORE-010).
 *
 * <p>Together, because they are the same promise from two sides: nothing is lost by accident. The
 * trash makes a deletion reversible for as long as the retention period lasts, and the history makes
 * every other change reversible for as long as the revisions are kept.
 */
@DisplayName("The trash and the history")
class TrashAndHistoryIT extends AbstractIntegrationTest {

  @Autowired private ItemService items;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private AppUserRepository users;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;

  @Test
  @DisplayName("keeps a deleted item recoverable, and lists it while it waits (REQ-CORE-009)")
  void trashThenRestore() {
    Tenant tenant = newTenant("trash-restore@example.org");
    TenantContext.runAs(
        tenant.tenantId(),
        () -> {
          UUID id = anItem(tenant, "Ladder");

          items.delete(id, tenant.userId());
          // Gone from the ordinary reads.
          assertThatThrownBy(() -> items.get(id)).isInstanceOf(NotFoundException.class);
          // And visible where a person goes looking for it.
          assertThat(items.trashed(null, 50).items()).extracting(ItemView::id).contains(id);

          ItemView back = items.restore(id, tenant.userId());
          assertThat(back.lifecycleState()).isEqualTo("ACTIVE");
          assertThat(items.get(id).id()).isEqualTo(id);
          assertThat(items.trashed(null, 50).items()).extracting(ItemView::id).doesNotContain(id);

          // Restoring twice is not an error, for the same reason deleting twice
          // is not: a client retrying a request it never saw the answer to.
          items.restore(id, tenant.userId());
        });
  }

  @Test
  @DisplayName("removes an item for good only after it has been trashed (REQ-CORE-009)")
  void purgeNeedsTheTrashFirst() {
    Tenant tenant = newTenant("trash-purge@example.org");
    TenantContext.runAs(
        tenant.tenantId(),
        () -> {
          UUID id = anItem(tenant, "Cardboard box");

          // The second stage is not a shortcut past the first.
          assertThatThrownBy(() -> items.purge(id, tenant.userId()))
              .isInstanceOf(IllegalStateException.class);

          items.delete(id, tenant.userId());
          items.purge(id, tenant.userId());

          assertThatThrownBy(() -> items.get(id)).isInstanceOf(NotFoundException.class);
          assertThat(items.trashed(null, 50).items()).extracting(ItemView::id).doesNotContain(id);

          // The history outlives the row: a removal that erased its own record
          // would leave nothing to say the thing ever existed.
          assertThat(historyOf(id))
              .extracting(RevisionLog.RevisionView::kind)
              .contains(RevisionLog.ChangeKind.PURGED);
        });
  }

  @Test
  @DisplayName("records what every change produced, in order (REQ-CORE-010)")
  void everyChangeIsRecorded() {
    Tenant tenant = newTenant("history-records@example.org");
    TenantContext.runAs(
        tenant.tenantId(),
        () -> {
          UUID id = anItem(tenant, "Kettle");
          items.update(
              id,
              new ItemService.UpdateItemCommand(
                  "Kettle, electric", "2 litres", null, BigDecimal.ONE, null, null, null, null),
              tenant.userId());
          items.delete(id, tenant.userId());
          items.restore(id, tenant.userId());

          assertThat(historyOf(id))
              .extracting(RevisionLog.RevisionView::kind)
              .containsExactly(
                  RevisionLog.ChangeKind.RESTORED,
                  RevisionLog.ChangeKind.TRASHED,
                  RevisionLog.ChangeKind.UPDATED,
                  RevisionLog.ChangeKind.CREATED);

          // Newest first, and each revision carries the state it produced.
          assertThat(historyOf(id).getLast().snapshot()).contains("Kettle");
        });
  }

  @Test
  @DisplayName("puts an earlier state back as a new revision (REQ-CORE-010)")
  void restoreARevision() {
    Tenant tenant = newTenant("history-restore@example.org");
    TenantContext.runAs(
        tenant.tenantId(),
        () -> {
          UUID id = anItem(tenant, "Original name");
          long created = historyOf(id).getFirst().revision();

          items.update(
              id,
              new ItemService.UpdateItemCommand(
                  "Changed name", null, null, BigDecimal.ONE, null, null, null, null),
              tenant.userId());
          assertThat(items.get(id).name()).isEqualTo("Changed name");

          ItemView back = items.restoreRevision(id, created, tenant.userId());
          assertThat(back.name()).isEqualTo("Original name");

          // A new revision rather than a rewind: the history still says what
          // happened and when, which is what makes it a history.
          assertThat(historyOf(id)).hasSize(3);
          assertThat(historyOf(id).getFirst().kind()).isEqualTo(RevisionLog.ChangeKind.UPDATED);
        });
  }

  /**
   * The whole history of one item, newest first.
   *
   * @param id the item
   * @return its revisions
   */
  private java.util.List<RevisionLog.RevisionView> historyOf(UUID id) {
    return items.history(id, null, 50).items();
  }

  /**
   * A digital item, which needs no place.
   *
   * @param tenant whose inventory
   * @param name what to call it
   * @return the item's id
   */
  private UUID anItem(Tenant tenant, String name) {
    return items.create(
            new ItemService.CreateItemCommand(
                null, null, name, null, ItemKind.DIGITAL, null, BigDecimal.ONE, null, null, null, null),
            tenant.userId())
        .item()
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
