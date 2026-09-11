/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.identity.infrastructure;

import de.greluc.homeinv.identity.domain.AppUser;
import java.util.Optional;
import java.util.UUID;
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
}
