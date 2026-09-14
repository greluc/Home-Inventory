/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tenancy.api;

import de.greluc.homeinv.platform.Page;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Who is in this tenant, and in what role (REQ-TEN-005, REQ-TEN-010).
 *
 * <p>Every method acts on the tenant the session is acting for. There is no tenant parameter, and
 * that is the point: the tenant comes from the authenticated principal and never from a request
 * (REQ-SEC-004), so a member administration that took one would be the one place the rule was
 * broken.
 */
public interface MembershipAdministration {

  /**
   * A member of the tenant.
   *
   * @param userId the account
   * @param email the address they sign in with
   * @param displayName what the interface calls them
   * @param role the built-in role they hold here
   * @param roleDefinitionId the tenant-owned role extending it (REQ-TEN-006), or null
   * @param roleName what to call the role: the tenant-owned role's name where there is one,
   *     otherwise the built-in name. A member list shows this rather than making a client join
   * @param scopeLocationId the part of the tree they are confined to (REQ-TEN-007), or null for the
   *     whole tenant
   * @param joinedAt when the membership was written
   */
  record MemberView(
      UUID userId,
      String email,
      String displayName,
      String role,
      UUID roleDefinitionId,
      String roleName,
      UUID scopeLocationId,
      Instant joinedAt) {}


  /**
   * One page of the tenant's members, oldest membership first.
   *
   * @param cursor an opaque cursor from a previous page, or null for the first
   * @param limit how many at most, capped at 200
   * @return the page
   */
  Page<MemberView> members(String cursor, int limit);

  /**
   * Changes what a member may do.
   *
   * <p>Two rules beyond the permission to call this at all. REQ-TEN-010: nobody grants a role whose
   * permissions they do not hold themselves, and {@code OWNER} is grantable only by an
   * {@code OWNER}, because ownership is a relationship rather than a permission set. And a tenant
   * keeps at least one owner: demoting the last one would leave a tenant nobody can administer and
   * nobody can delete.
   *
   * @param userId the member to change
   * @param role the new built-in role, one of the six names
   * @param roleDefinitionId a tenant-owned role extending it (REQ-TEN-006), or null for a plain
   *     built-in role. Where it is given, its base must be the role named above — a definition and
   *     a base that disagreed would be two answers to what the person may do
   * @param scopeLocationId a location to confine them to (REQ-TEN-007), or null for the whole
   *     tenant. Somebody scoped to the garage sees the garage and everything below it, and nothing
   *     else — items with no place at all included, because a thing with no place is in nobody's
   *     garage
   * @param actor who is making the change
   * @return the member as they now stand
   * @throws de.greluc.homeinv.platform.NotFoundException when the person is not a member here, or
   *     when this tenant has no such role definition
   * @throws RoleEscalationException when the actor may not grant that role
   * @throws LastOwnerException when the change would leave the tenant without an owner
   */
  MemberView changeRole(
      UUID userId, String role, UUID roleDefinitionId, UUID scopeLocationId, UUID actor);

  /**
   * Removes somebody from the tenant.
   *
   * <p>The membership is tombstoned rather than deleted (07 §7.1, rule 5): audit entries name the
   * person, and a row that vanished would leave those entries pointing at nothing. Removing
   * somebody who is not a member is not an error, for the same reason deleting twice is not.
   *
   * @param userId the member to remove
   * @param actor who is removing them
   * @throws LastOwnerException when they are the tenant's last owner
   * @throws RoleEscalationException when the actor holds fewer permissions than the member they are
   *     removing, which is the same escalation as granting a role one does not hold
   */
  void remove(UUID userId, UUID actor);
}
