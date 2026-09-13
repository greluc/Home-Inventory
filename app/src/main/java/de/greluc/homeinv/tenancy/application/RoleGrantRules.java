/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tenancy.application;

import de.greluc.homeinv.authorization.api.AccessControl;
import de.greluc.homeinv.tenancy.api.RoleEscalationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Turns "may this person grant that role" into this block's own refusal (REQ-TEN-010).
 *
 * <p>The <em>decision</em> is not here. {@link AccessControl#mayGrant(String, String)} makes it,
 * because it is a question about what a role is worth and {@code authorization} is the only block
 * allowed to answer that (04 §4.3) — a rule ArchUnit enforces, and which this class was written
 * against on its first attempt. What is here is the consequence: an escalation is a tenancy error,
 * carrying both roles, answered as {@code 403} and logged.
 */
@Component
@RequiredArgsConstructor
class RoleGrantRules {

  private final AccessControl accessControl;

  /**
   * Refuses a grant the actor is not entitled to make.
   *
   * @param actorRole the role the actor holds in this tenant
   * @param targetRole the role being granted or withdrawn
   * @throws RoleEscalationException when the actor may not
   */
  void requireGrantable(String actorRole, String targetRole) {
    if (!accessControl.mayGrant(actorRole, targetRole)) {
      throw new RoleEscalationException(String.valueOf(actorRole), String.valueOf(targetRole));
    }
  }
}
