/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.authorization.api;

import java.util.OptionalInt;
import java.util.UUID;

/**
 * What this block is allowed to ask about an account (ADR-0057).
 *
 * <p>A port declared here and implemented by {@code identity}, which owns the columns. The
 * direction is what keeps the blocks acyclic: {@code identity} already depends on this block's
 * vocabulary, and a call the other way — {@code authorization} reaching into {@code identity} —
 * would close a cycle. It is the same arrangement {@code catalog.AttributeUsage} has with
 * {@code inventory} and {@code locations}, for the same reason.
 *
 * <p>Every method takes a user id rather than reading the current caller. These are asked in places
 * where there is no session yet — the {@code bootstrap} service, a test — and a port that silently
 * read a thread-local would be one that answered about the wrong person exactly there.
 */
public interface AccountEntitlements {

  /**
   * Whether an account holds an entitlement.
   *
   * <p>Read from the database on every call rather than from the session. That is the difference
   * between an entitlement and a role: a role travels in the principal because it is re-established
   * at login and changes with a membership; an entitlement is granted by an operator to somebody who
   * may be signed in at the time, and a grant that took effect only after a re-login would be a
   * grant an operator watches not happen.
   *
   * @param userId the account
   * @param entitlement what is being asked about
   * @return {@code true} when it is granted and the account is neither locked nor deleted
   */
  boolean holds(UUID userId, Entitlement entitlement);

  /**
   * How many tenants this account may own in total.
   *
   * <p>The bound on {@link Entitlement#CREATE_TENANT}, and it lives beside the grant because it is
   * read in the same breath: "may they, and have they reached the end of it". An empty result means
   * the account carries no override of its own and the instance-wide default applies — which
   * {@code tenancy} resolves, because the default is a deployment setting and not a fact about the
   * person.
   *
   * @param userId the account
   * @return the per-account limit, or empty when there is none
   */
  OptionalInt tenantLimit(UUID userId);
}
