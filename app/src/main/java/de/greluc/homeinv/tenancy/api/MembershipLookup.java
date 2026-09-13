/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tenancy.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The memberships of one person, answerable without a tenant context.
 *
 * <p>A port, not a repository: the login flow needs one fact - which tenant this person acts for -
 * and giving it the membership repository would let it read and write tenant data it has no
 * business touching. The interface is in the published {@code api} package because it is the only
 * part of {@code tenancy} another block may look at (REQ-NFR-020).
 *
 * <p>Every method here works where {@code app.tenant_id} is not set, which is what makes this
 * different from every other read in the system and why it goes through the {@code SECURITY
 * DEFINER} function of 07 §7.5. Choosing which tenant to act for cannot require already acting for
 * one.
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
   * @param tenantName the tenant's display name. Read here because a switcher that offers ids is
   *     not a switcher, and the person choosing has no context in which to look the names up
   * @param role the membership's role, as stored - one of the six in the table's check constraint
   * @param roleDefinitionId the tenant-owned role extending it (REQ-TEN-006), or null. Read here
   *     for the same reason the role is: both are established at login, and re-reading per request
   *     would be a second path to the same fact
   * @param scopeLocationId the part of the tree this membership is confined to (REQ-TEN-007), or
   *     null for the whole tenant
   */
  record Membership(
      UUID tenantId,
      String tenantName,
      String role,
      UUID roleDefinitionId,
      UUID scopeLocationId) {}

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

  /**
   * Every live membership of a person, oldest first.
   *
   * <p>What {@code GET /api/v1/me/tenants} shows and what the switch checks against (REQ-TEN-003),
   * and what a tenant quota is counted from (REQ-TEN-002). Deliberately unpaginated: a person with
   * more memberships than fit in one answer has hit the quota long before.
   *
   * @param userId the person
   * @return their memberships, in the order they joined
   */
  List<Membership> membershipsOf(UUID userId);
}
