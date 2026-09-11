/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tenancy.infrastructure;

import de.greluc.homeinv.tenancy.domain.Membership;
import java.util.List;
import java.util.UUID;
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
}
