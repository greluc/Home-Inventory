/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tenancy.infrastructure;

import de.greluc.homeinv.tenancy.domain.Membership;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence for memberships. */
public interface MembershipRepository extends JpaRepository<Membership, UUID> {

  /**
   * The live memberships of a user, oldest first.
   *
   * <p>Ordered, so "the tenant to start in" is a stable answer rather than whichever row the
   * planner returned today.
   *
   * <p>This query runs during login, before any tenant context exists, so row-level security would
   * hide every row. It is therefore marked to run outside the tenant policy by being executed in
   * the bootstrap transaction - see {@code MembershipLookupAdapter}.
   *
   * @param userId the person
   * @return their memberships, oldest first
   */
  @Query(
      "select m from Membership m where m.userId = :userId and m.deletedAt is null "
          + "order by m.createdAt asc")
  List<Membership> findLiveByUser(@Param("userId") UUID userId);

  /**
   * Somebody's live membership in the tenant the session is acting for.
   *
   * <p>No tenant parameter: row-level security scopes this to the caller's tenant, which is also
   * why the answer is at most one row — the partial unique index allows one live membership per
   * person per tenant.
   *
   * @param userId the person
   * @return their membership here, or empty
   */
  @Query("select m from Membership m where m.userId = :userId and m.deletedAt is null")
  Optional<Membership> findLiveInTenant(@Param("userId") UUID userId);

  /**
   * How many owners this tenant has besides one person.
   *
   * <p>The question "would this leave the tenant without an owner", asked as a count rather than as
   * a list, because the answer is used as a yes or no and a list of owners is not what the caller
   * needs.
   *
   * @param userId the person being demoted or removed
   * @return how many other live owners there are
   */
  @Query(
      "select count(m) from Membership m where m.role = 'OWNER' and m.deletedAt is null "
          + "and m.userId <> :userId")
  long countOtherOwners(@Param("userId") UUID userId);

  /**
   * The first page of this tenant's live members, oldest first.
   *
   * @param limit how many at most
   * @return the memberships
   */
  @Query("select m from Membership m where m.deletedAt is null order by m.createdAt asc, m.id asc")
  List<Membership> findPage(Limit limit);

  /**
   * The members after a keyset position.
   *
   * @param since the creation instant the previous page ended at
   * @param id the id it ended at, breaking a tie within the same instant
   * @param limit how many at most
   * @return the next memberships
   */
  @Query(
      "select m from Membership m where m.deletedAt is null "
          + "and (m.createdAt > :since or (m.createdAt = :since and m.id > :id)) "
          + "order by m.createdAt asc, m.id asc")
  List<Membership> findPageAfter(
      @Param("since") Instant since, @Param("id") UUID id, Limit limit);
}
