/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.infrastructure;

import de.greluc.homeinv.inventory.domain.Item;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
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
   *     told apart from an unknown one (REQ-SEC-025)
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
   * One page of the tenant's trashed items, oldest first.
   *
   * <p>Ordered by creation and id rather than by when they were trashed, because that is the pair
   * the keyset cursor everywhere else in this application resumes from — and the order a person
   * reads the trash in is a client's business.
   *
   * @param tenantId the tenant
   * @param page how many at most
   * @return the items in the trash
   */
  @Query(
      "select i from Item i where i.tenantId = :tenantId and i.deletedAt is not null "
          + "order by i.createdAt, i.id")
  List<Item> findTrashed(@Param("tenantId") UUID tenantId, Pageable page);

  /**
   * The next page of trashed items, after the position a cursor names.
   *
   * @param tenantId the tenant
   * @param createdAt where the last page ended
   * @param id the tie-breaker for two rows created in the same microsecond
   * @param page how many at most
   * @return the next items in the trash
   */
  @Query(
      "select i from Item i where i.tenantId = :tenantId and i.deletedAt is not null "
          + "and (i.createdAt > :createdAt or (i.createdAt = :createdAt and i.id > :id)) "
          + "order by i.createdAt, i.id")
  List<Item> findTrashedAfter(
      @Param("tenantId") UUID tenantId,
      @Param("createdAt") java.time.Instant createdAt,
      @Param("id") UUID id,
      Pageable page);

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

  /**
   * Whether a location still holds a live item.
   *
   * <p>Serves the {@code ItemLocationUsage} port. It lives here rather than as a query in
   * {@code locations} because no block reads another block's schema (REQ-NFR-021).
   *
   * @param tenantId the tenant
   * @param locationId the location
   * @return {@code true} when at least one live item names it
   */
  @Query("select count(i) > 0 from Item i where i.tenantId = :tenantId "
      + "and i.locationId = :locationId and i.deletedAt is null")
  boolean existsLiveInLocation(@Param("tenantId") UUID tenantId, @Param("locationId") UUID locationId);
}
