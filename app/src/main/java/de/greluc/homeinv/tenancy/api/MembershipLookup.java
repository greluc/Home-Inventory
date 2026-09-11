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
   * A membership: the tenant, and the role held in it.
   *
   * <p>The role travels with the tenant because both are read at login, in the one moment there is
   * no tenant context to read them with (07 §7.5). Re-reading the role per request would mean
   * reading {@code tenancy.membership} under the caller's own policies, which is a second path to
   * the same fact and one more place for the two to disagree.
   *
   * @param tenantId the tenant the session acts for
   * @param role the membership's role, as stored - one of the six in the table's check constraint
   */
  record Membership(UUID tenantId, String role) {}

  /**
   * The membership a user acts under when they log in.
   *
   * <p>Stage 0 has exactly one per user. Stage 1 lets a user belong to several and switch between
   * them without re-authenticating (REQ-TEN-003); this then returns the one to start in, and
   * everything else stays as it is.
   *
   * @param userId the person
   * @return their membership, or empty when they belong to none - which is a data problem, not a
   *     legitimate state
   */
  Optional<Membership> primaryMembershipOf(UUID userId);
}
