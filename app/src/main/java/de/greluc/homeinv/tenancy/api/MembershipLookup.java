/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tenancy.api;

import java.util.Optional;
import java.util.UUID;

/**
 * What {@code identity} is allowed to ask {@code tenancy}.
 *
 * <p>A port, not a repository: the login flow needs one fact - which tenant this person acts for -
 * and giving it the membership repository would let it read and write tenant data it has no
 * business touching. The interface is in the published {@code api} package because it is the only
 * part of {@code tenancy} another block may look at (REQ-NFR-020).
 */
public interface MembershipLookup {

  /**
   * The tenant a user acts for when they log in.
   *
   * <p>Stage 0 has exactly one per user. Stage 1 lets a user belong to several and switch between
   * them without re-authenticating (REQ-TEN-003); this then returns the one to start in, and
   * everything else stays as it is.
   *
   * @param userId the person
   * @return their tenant, or empty when they belong to none - which is a data problem, not a
   *     legitimate state
   */
  Optional<UUID> primaryTenantOf(UUID userId);
}
