/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tenancy.infrastructure;

import de.greluc.homeinv.tenancy.domain.Invitation;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence for invitations.
 *
 * <p>Every query here runs under the tenant context, so row-level security scopes it — the tenant
 * is never a parameter. The one read that cannot work that way is the lookup by token, which
 * happens before any tenant is known and goes through {@code InvitationLookupAdapter} and the
 * {@code SECURITY DEFINER} function of migration {@code V23}.
 */
public interface InvitationRepository extends JpaRepository<Invitation, UUID> {

  /**
   * The open invitation for an address in this tenant, if there is one.
   *
   * @param email the address, folded to lower case by the caller
   * @param now the instant to judge the expiry at
   * @return the open invitation, or empty
   */
  @Query(
      "select i from Invitation i where lower(i.email) = :email and i.acceptedAt is null "
          + "and i.revokedAt is null and i.deletedAt is null and i.expiresAt > :now")
  Optional<Invitation> findOpenFor(@Param("email") String email, @Param("now") Instant now);

  /**
   * The first page of this tenant's invitations, newest first.
   *
   * @param limit how many at most
   * @return the invitations
   */
  @Query("select i from Invitation i where i.deletedAt is null order by i.createdAt desc, i.id desc")
  List<Invitation> findPage(Limit limit);

  /**
   * The invitations after a keyset position.
   *
   * @param before the creation instant the previous page ended at
   * @param id the id it ended at, breaking a tie within the same instant
   * @param limit how many at most
   * @return the next invitations
   */
  @Query(
      "select i from Invitation i where i.deletedAt is null "
          + "and (i.createdAt < :before or (i.createdAt = :before and i.id < :id)) "
          + "order by i.createdAt desc, i.id desc")
  List<Invitation> findPageAfter(
      @Param("before") Instant before, @Param("id") UUID id, Limit limit);
}
