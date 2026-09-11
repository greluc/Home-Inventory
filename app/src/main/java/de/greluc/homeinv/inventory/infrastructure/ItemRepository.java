/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.infrastructure;

import de.greluc.homeinv.inventory.domain.Item;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence for the item aggregate.
 *
 * <p>Every query filters by tenant even though row-level security already does. That is not
 * redundancy for its own sake — it is the first of the two independent lines the design calls for
 * (07 §7.5). If the tenant context were ever missing, RLS returns nothing and this filter returns
 * nothing; if RLS were ever misconfigured, this filter still holds. Neither is trusted to be the
 * only one.
 *
 * <p>Deleted items are excluded here rather than by callers. A tombstone is a fact reconciliation
 * needs (07 §7.1, rule 5), and forgetting the predicate at one call site would surface deleted
 * items in a list — the kind of defect that is noticed by a user, not by a test.
 */
public interface ItemRepository extends JpaRepository<Item, UUID> {

  /**
   * Finds one live item of a tenant.
   *
   * @param tenantId the tenant, from the authenticated context
   * @param id the item
   * @return the item, or empty when it does not exist, is deleted, or belongs to another tenant —
   *     the three cases are deliberately indistinguishable to the caller, so a foreign id cannot be
   *     told apart from an unknown one (REQ-SEC-016)
   */
  @Query("select i from Item i where i.tenantId = :tenantId and i.id = :id and i.deletedAt is null")
  Optional<Item> findLive(@Param("tenantId") UUID tenantId, @Param("id") UUID id);

  /**
   * Finds one item of a tenant including a deleted one.
   *
   * <p>Used by the delete path, which must answer a repeated request the same way it answered the
   * first: a client that never saw the response retries, and a second delete is a success, not a
   * {@code 404}.
   *
   * @param tenantId the tenant
   * @param id the item
   * @return the item whether live or tombstoned, or empty when the tenant has no such item
   */
  @Query("select i from Item i where i.tenantId = :tenantId and i.id = :id")
  Optional<Item> findAny(@Param("tenantId") UUID tenantId, @Param("id") UUID id);

  /**
   * Whether a live item with this id already exists for the tenant.
   *
   * <p>The client may choose the id (ADR-0016), so a collision is a case the API must answer
   * clearly rather than a database constraint violation the user sees as a {@code 500}.
   *
   * @param tenantId the tenant
   * @param id the candidate id
   * @return {@code true} when the id is taken
   */
  @Query("select count(i) > 0 from Item i where i.tenantId = :tenantId and i.id = :id")
  boolean existsForTenant(@Param("tenantId") UUID tenantId, @Param("id") UUID id);
}
