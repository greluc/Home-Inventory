/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tenancy.application;

import de.greluc.homeinv.authorization.api.AccessControl;
import de.greluc.homeinv.authorization.api.RoleRef;
import de.greluc.homeinv.tenancy.api.RoleEscalationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Turns "may this person grant that role" into this block's own refusal (REQ-TEN-010).
 *
 * <p>The <em>decision</em> is not here. {@link AccessControl#mayGrant(RoleRef, RoleRef)} makes it,
 * because it is a question about what a role is worth and {@code authorization} is the only block
 * allowed to answer that (04 §4.3) — a rule ArchUnit enforces, and which this class was written
 * against on its first attempt. What is here is the consequence: an escalation is a tenancy error,
 * carrying both roles, answered as {@code 403} and logged.
 *
 * <p>A tenant-owned role is compared the same way, because what is compared is the permission set
 * rather than the rung. An administrator who defined a role adding something they do not hold and
 * then assigned it would be doing exactly what the requirement forbids, and the set test is what
 * catches it.
 */
@Component
@RequiredArgsConstructor
class RoleGrantRules {

  private final AccessControl accessControl;

  /**
   * Refuses a grant the actor is not entitled to make.
   *
   * @param actor the role the actor holds in this tenant
   * @param target the role being granted or withdrawn
   * @throws RoleEscalationException when the actor may not
   */
  void requireGrantable(RoleRef actor, RoleRef target) {
    if (!accessControl.mayGrant(actor, target)) {
      throw new RoleEscalationException(nameOf(actor), nameOf(target));
    }
  }

  /**
   * What the refusal calls a role.
   *
   * <p>The built-in name, with the tenant-owned one appended where there is one, so that a message
   * says "ADMIN cannot grant MEMBER+3f2c…" rather than naming two rungs that look identical.
   *
   * @param role the reference
   * @return a name for a person to read
   */
  private static String nameOf(RoleRef role) {
    if (role == null) {
      return "none";
    }
    return role.definitionId() == null
        ? String.valueOf(role.builtIn())
        : role.builtIn() + "+" + role.definitionId();
  }
}
