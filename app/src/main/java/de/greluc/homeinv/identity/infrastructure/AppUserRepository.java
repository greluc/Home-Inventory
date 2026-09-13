/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.identity.infrastructure;

import de.greluc.homeinv.identity.domain.AppUser;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence for users.
 *
 * <p>No tenant filter, because the table is instance-wide (07 s7.1): the lookup happens before any
 * tenant is known. That is exactly why nothing else may query it freely - every other path to a
 * user goes through a membership of the caller's own tenant, which is RLS-protected.
 */
public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

  /**
   * Finds a user by login address, case-insensitively.
   *
   * <p>Matches the unique index, which is on {@code lower(email)}. Treating two spellings of the
   * same address as two accounts is a way to lock somebody out of their own.
   *
   * @param email the address as entered
   * @return the user, or empty when no live account has that address
   */
  @Query("select u from AppUser u where lower(u.email) = lower(:email) and u.deletedAt is null")
  Optional<AppUser> findByEmail(@Param("email") String email);

  /**
   * The first page of the live accounts that administer the instance (ADR-0057).
   *
   * <p>Oldest first: the first operator is the one the bootstrap service made, and an operator
   * reading this list wants to see it there. Ordered by {@code createdAt} and {@code id} together,
   * because two accounts created in the same millisecond would otherwise page unstably.
   *
   * @param limit how many at most
   * @return the instance operators
   */
  @Query("select u from AppUser u where u.instanceOperator = true and u.deletedAt is null"
      + " order by u.createdAt asc, u.id asc")
  List<AppUser> findInstanceOperators(Limit limit);

  /**
   * The operators after a keyset position.
   *
   * <p>A keyset and not an offset: an offset over a list somebody is editing skips and repeats rows
   * (08 §8.2), and an operator list is edited exactly while it is being read.
   *
   * @param since the creation instant the previous page ended at
   * @param id the id it ended at, breaking a tie within the same instant
   * @param limit how many at most
   * @return the next operators
   */
  @Query("select u from AppUser u where u.instanceOperator = true and u.deletedAt is null"
      + " and (u.createdAt > :since or (u.createdAt = :since and u.id > :id))"
      + " order by u.createdAt asc, u.id asc")
  List<AppUser> findInstanceOperatorsAfter(
      @Param("since") Instant since, @Param("id") UUID id, Limit limit);
}
