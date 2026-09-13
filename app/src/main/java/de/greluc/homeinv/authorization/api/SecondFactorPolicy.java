/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.authorization.api;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Which roles may not be used without a second factor (REQ-AUTH-003).
 *
 * <p>The requirement names two groups: {@code OWNER} and {@code ADMIN}, and "any role that may read
 * `sensitive` fields" (REQ-SEC-015). The second is not a fixed list — a tenant grants field
 * visibility to whichever of its roles it likes — so it is a question asked of the rules rather
 * than a constant.
 *
 * <h2>The role is granted; using it is what waits</h2>
 *
 * <p>An account with no authenticator still becomes an owner: creating a tenant makes one, and the
 * first owner of an instance is made by a one-shot that has no way to ask anybody for a code.
 * Refusing the grant would mean either a tenant nobody owns or an exception for exactly the role
 * that may do the most. So the membership stands and every request in that tenant is refused with
 * {@link SecondFactorMissingException} until the factor exists — one rule that covers the bootstrap,
 * the first tenant and every membership that predates this.
 */
public interface SecondFactorPolicy {

  /**
   * How long proving the second factor counts for (REQ-AUTH-011).
   *
   * <p>Fifteen minutes: one stretch of work — granting a few roles, reading a purchase price,
   * starting an export — on one code. Shorter asks again in the middle of what somebody is doing,
   * which teaches them to keep the app open beside the keyboard; longer stops being a
   * re-confirmation.
   */
  Duration RECONFIRMATION_WINDOW = Duration.ofMinutes(15);

  /**
   * Whether a role may be used only by an account with a second factor.
   *
   * @param role the built-in role and, where there is one, the definition extending it
   * @return true for {@code OWNER} and {@code ADMIN}, and for any role holding a grant on a
   *     sensitive field in the tenant being acted for
   */
  boolean requiresSecondFactor(RoleRef role);

  /**
   * Refuses a session whose role needs a factor the account does not have.
   *
   * @param role the role the session holds
   * @param userId the account holding it
   * @throws SecondFactorMissingException when the role requires one and the account has none
   */
  void requireEnrolled(RoleRef role, UUID userId);

  /**
   * Whether a proof of the second factor is recent enough for a critical operation.
   *
   * @param provedAt when the factor was last proved in this session, or null when it never was
   * @return true when it was proved within {@link #RECONFIRMATION_WINDOW}
   */
  boolean provedRecently(Instant provedAt);

}
