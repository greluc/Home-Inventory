/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.authorization.api;

import java.util.UUID;

/**
 * Which role somebody holds: one of the six, and optionally a tenant-owned one extending it.
 *
 * <p>Both together, never one or the other. A tenant-owned role EXTENDS a built-in one (04 §4.3),
 * so {@code builtIn} is always present and always meaningful — it is what the person may do at
 * least, and what they still may do if the definition is later removed.
 *
 * @param builtIn the built-in role's name, as {@code tenancy.membership.role} stores it
 * @param definitionId the tenant-owned role extending it, or null when there is none
 */
public record RoleRef(String builtIn, UUID definitionId) {

  /**
   * A plain built-in role.
   *
   * @param builtIn the role's name
   * @return the reference
   */
  public static RoleRef of(String builtIn) {
    return new RoleRef(builtIn, null);
  }
}
