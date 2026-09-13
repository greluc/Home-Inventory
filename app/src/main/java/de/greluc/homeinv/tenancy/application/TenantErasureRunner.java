/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tenancy.application;

import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.platform.TenantErasure;
import de.greluc.homeinv.tenancy.infrastructure.ErasureCertificateWriter;
import de.greluc.homeinv.tenancy.infrastructure.TenantErasureQueries;
import de.greluc.homeinv.tenancy.infrastructure.TenantTombstone;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Carries out the erasures whose grace period has run out (REQ-TEN-011, REQ-PRIV-005).
 *
 * <h2>One pass, and ADR-0060 says why it is not a broker</h2>
 *
 * <p>05 §5.9 drew this as {@code TenantDeletionConfirmed} on RabbitMQ, each block consuming it and
 * reporting back, and a certificate once all had. The outcome is identical and the state is not: a
 * choreography has a middle — "three of eight have reported" — and a lost message leaves a tenant
 * half erased until somebody notices. This walks the blocks in order, each in its own transaction,
 * and is resumable by construction: a run interrupted between two blocks is finished by the next
 * one, because a block with nothing left to remove reports zero rather than failing.
 *
 * <h2>Which order, and why it is not the list's</h2>
 *
 * <p>The blocks reference each other's tables — an item names a place and a type version, a
 * membership names a location and a role definition — and PostgreSQL refuses to remove a row
 * something still points at. Each block declares where it sits with {@link TenantErasure#order()}
 * and names the foreign key that put it there; this class sorts by it and refuses to start when two
 * blocks claim the same position.
 *
 * <p>The tenant row is marked erased after all of them, by {@link TenantTombstone}. That mark is
 * what takes the tenant off the due list, so setting it earlier would end a run that had not
 * finished and leave rows nothing ever comes back for.
 *
 * <h2>Finding the tenants at all</h2>
 *
 * <p>A query that spans tenants, which {@code homeinv_app} cannot make — it has neither
 * {@code BYPASSRLS} nor a way to enumerate tenants (13 §13.8 makes the same point about the
 * catch-up scan). It goes through the {@code SECURITY DEFINER} function of migration {@code V31},
 * which returns ids and instants and nothing else.
 */
@Service
@Slf4j
public class TenantErasureRunner {

  private final TenantErasureQueries due;
  private final ErasureCertificateWriter certificates;
  private final TenantTombstone tombstone;
  private final List<TenantErasure> blocks;
  private final long graceDays;

  /**
   * @param due the lookup that spans tenants
   * @param certificates where the evidence is written
   * @param tombstone what marks the tenant erased once every block has reported
   * @param blocks every block that holds a tenant's data, in whatever order the classpath scan
   *     produced them — this class sorts them
   * @param graceDays how long a request waits, from {@code HOMEINV_TENANT_ERASURE_GRACE_DAYS}
   * @throws IllegalStateException when two blocks claim the same position, which would make the
   *     order depend on the scan
   */
  public TenantErasureRunner(
      TenantErasureQueries due,
      ErasureCertificateWriter certificates,
      TenantTombstone tombstone,
      List<TenantErasure> blocks,
      @Value("${HOMEINV_TENANT_ERASURE_GRACE_DAYS:30}") long graceDays) {
    this.due = due;
    this.certificates = certificates;
    this.tombstone = tombstone;
    // Sorted once, here, rather than trusted to injection order: Spring's list is
    // whatever the classpath scan produced, and an order that happened to be
    // right would be right until somebody renamed a class.
    this.blocks = blocks.stream().sorted(Comparator.comparingInt(TenantErasure::order)).toList();
    this.graceDays = graceDays;

    // At startup rather than at the first erasure. A duplicate position is a
    // mistake a reviewer makes once and a run discovers a month later, when a
    // tenant is already half gone.
    List<Integer> ambiguous =
        blocks.stream()
            .collect(Collectors.groupingBy(TenantErasure::order, Collectors.counting()))
            .entrySet()
            .stream()
            .filter(entry -> entry.getValue() > 1)
            .map(java.util.Map.Entry::getKey)
            .toList();
    if (!ambiguous.isEmpty()) {
      throw new IllegalStateException(
          "Two building blocks claim the same erasure position " + ambiguous
              + "; see TenantErasure.order().");
    }
  }

  /**
   * Erases every tenant whose grace period has elapsed.
   *
   * <p>Repeatable and cancellable, and it logs its start, its end and its scope — which is what
   * {@code REQ-NFR-050} asks of a recurring task. A tenant that fails is logged and the run
   * continues: one tenant's broken erasure must not stop everybody else's.
   *
   * @return how many tenants were erased
   */
  public int eraseDueTenants() {
    List<TenantErasureQueries.DueTenant> tenants = due.dueFor(Duration.ofDays(graceDays));
    if (tenants.isEmpty()) {
      return 0;
    }

    log.info("Erasing {} tenant(s) whose grace period has elapsed.", tenants.size());
    int erased = 0;
    for (TenantErasureQueries.DueTenant tenant : tenants) {
      try {
        erase(tenant);
        erased++;
      } catch (RuntimeException failed) {
        // Logged and carried on. One tenant's failure is not a reason to leave
        // everybody else's request unhonoured, and the next run picks this one up
        // again from wherever it stopped.
        log.error("Could not erase tenant {}; the next run will resume it.", tenant.tenantId(),
            failed);
      }
    }
    log.info("Erased {} of {} tenant(s).", erased, tenants.size());
    return erased;
  }

  /**
   * Erases one tenant and writes its certificate.
   *
   * @param tenant which tenant, and when it was asked for
   */
  private void erase(TenantErasureQueries.DueTenant tenant) {
    UUID tenantId = tenant.tenantId();
    List<TenantErasure.BlockReport> report = new ArrayList<>();

    // Read before anything goes: the certificate names the tenant, and the name
    // is one of the things being erased.
    ErasureCertificateWriter.Snapshot snapshot =
        TenantContext.callAs(tenantId, () -> certificates.snapshotOf(tenantId));

    for (TenantErasure block : blocks) {
      // Each in its own transaction and inside the tenant's own context, so the
      // policies scope every delete and a failure in one block leaves the others
      // done rather than rolling back an hour of work.
      report.add(TenantContext.callAs(tenantId, () -> block.erase(tenantId)));
    }

    // Now, and not a block earlier: this mark is what takes the tenant off the
    // due list, and a run that stopped before it is one the next sweep resumes.
    TenantContext.runAs(tenantId, () -> tombstone.tombstone(tenantId));

    // Outside the tenant context: the certificate table is instance-wide, and a
    // context set here would be one the table's absent policy ignores anyway.
    certificates.write(tenantId, snapshot, report);
    log.warn("Tenant {} was erased; a certificate has been issued.", tenantId);
  }
}
