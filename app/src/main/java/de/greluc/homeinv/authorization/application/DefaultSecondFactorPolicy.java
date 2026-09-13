/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.authorization.application;

import de.greluc.homeinv.authorization.api.FieldVisibility;
import de.greluc.homeinv.authorization.api.Role;
import de.greluc.homeinv.authorization.api.RoleRef;
import de.greluc.homeinv.authorization.api.SecondFactorMissingException;
import de.greluc.homeinv.authorization.api.SecondFactorPolicy;
import de.greluc.homeinv.authorization.api.SecondFactorStatus;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Decides which roles need a second factor, and refuses the ones that do without (REQ-AUTH-003).
 *
 * <h2>Two groups, and only one of them is a constant</h2>
 *
 * <p>{@code OWNER} and {@code ADMIN} always. Beyond them, "any role that may read sensitive fields"
 * — which is whatever the tenant has granted, so it is read from the rules rather than listed here.
 * The reason both groups are in one requirement is {@code REQ-AUTH-011}: it asks for the factor
 * again before a sensitive field is shown, and a role that could hold no factor at all would make
 * that re-confirmation impossible to satisfy.
 *
 * <h2>What this costs per request</h2>
 *
 * <p>Nothing for {@code OWNER} and {@code ADMIN}, who are decided without a query, and one
 * {@code EXISTS} for everybody else — and only until they are refused or pass. A tenant that has
 * granted no sensitive field at all answers it from an index with no rows.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DefaultSecondFactorPolicy implements SecondFactorPolicy {

  /** The roles that read every sensitive field unless a rule narrows it, so always protected. */
  private static final List<String> ALWAYS =
      List.of(Role.OWNER.name(), Role.ADMIN.name());

  private final FieldVisibility fieldVisibility;
  private final SecondFactorStatus secondFactors;

  @Override
  public boolean requiresSecondFactor(RoleRef role) {
    if (role == null || role.builtIn() == null) {
      // No role means no membership in this tenant: an instance operator, or
      // somebody between tenants. There is nothing here for a factor to protect,
      // and what such a session can reach is decided elsewhere.
      return false;
    }
    return ALWAYS.contains(role.builtIn()) || fieldVisibility.readsSensitiveFields(role);
  }

  @Override
  public void requireEnrolled(RoleRef role, UUID userId) {
    if (!requiresSecondFactor(role) || secondFactors.isEnrolled(userId)) {
      return;
    }
    log.info(
        "Account {} holds {} here and has no second factor; refused.", userId, role.builtIn());
    throw new SecondFactorMissingException(
        "This role requires a second factor. Set one up at /api/v1/auth/mfa/totp and sign in"
            + " again.");
  }

}
