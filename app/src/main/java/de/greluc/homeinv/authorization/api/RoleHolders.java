/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.authorization.api;

import java.util.List;
import java.util.UUID;

/**
 * Who holds a role in the tenant being acted for (REQ-AUTH-003).
 *
 * <p>A port declared here and implemented by {@code tenancy}, which owns the memberships.
 * {@code tenancy} already depends on this block for what a role may grant, so the arrow runs the
 * way it already runs; asking {@code tenancy} directly would close a cycle.
 *
 * <p>It exists for one rule: sensitive field visibility may not be granted to a role whose members
 * have no second factor, because the grant would otherwise make a person able to read a purchase
 * price with a password alone (REQ-AUTH-003, REQ-SEC-015).
 */
public interface RoleHolders {

  /**
   * The accounts holding a role, in the tenant being acted for.
   *
   * @param role the built-in role and, where there is one, the definition extending it
   * @return the accounts of the live memberships that hold exactly this role. A definition is
   *     matched on the definition; a plain built-in role matches memberships with no definition,
   *     because somebody holding a definition extending {@code MEMBER} does not hold {@code MEMBER}
   */
  List<UUID> holdersOf(RoleRef role);
}
