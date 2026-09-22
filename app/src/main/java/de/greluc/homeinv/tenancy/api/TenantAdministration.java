/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tenancy.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Suspending a tenant and letting it back in, as the operator (REQ-SEC-082, REQ-TEN-011).
 *
 * <h2>Why this is not {@link TenantLifecycle}</h2>
 *
 * <p>{@link TenantLifecycle} is a <b>tenant's</b> view of its own state: it reads the state of the
 * tenant in context and it is how a tenant asks for its own erasure. This is the <b>operator's</b>,
 * naming a tenant it is not a member of, and every method here therefore crosses the tenant policy
 * through a {@code SECURITY DEFINER} function.
 *
 * <p>The two are kept visibly separate for the reason {@code QuotaAdministration} is separate from
 * {@code QuotaGuard}: a method that worked in both places would be one somebody later calls inside
 * a tenant context, where it would quietly do something else.
 *
 * <h2>Two states, and the other two are not reachable from here</h2>
 *
 * <p>An operator suspends and reinstates. They do not start an erasure — that has a grace period
 * and a revocation token and belongs to the tenant — and they cannot end one either, because
 * withdrawing a deletion request is what the token is for. A call naming a tenant that is waiting
 * to be erased is refused rather than ignored.
 */
public interface TenantAdministration {

  /** The two states an operator moves a tenant between. */
  enum State {
    /** Answering normally. */
    ACTIVE,

    /**
     * Answering nothing.
     *
     * <p>Every request for this tenant gets {@code 403 tenant-inaccessible} from the interceptor
     * that already handles it — which is what makes this an <i>immediate</i> measure: it takes
     * effect on the next request, without ending a session, revoking a token or touching a row of
     * the tenant's data.
     */
    SUSPENDED
  }

  /**
   * Moves a tenant between the two operator states.
   *
   * @param tenantId which tenant
   * @param state where to put it
   * @param actor the operator making the change, for the audit trail
   * @return the state as it now stands, or empty when there is no such tenant
   * @throws IllegalStateException when the tenant is waiting to be erased or already erased, which
   *     are states this door does not open
   */
  Optional<State> setState(UUID tenantId, State state, UUID actor);

  /**
   * The lifecycle state of a tenant, as the operator view shows it.
   *
   * <p>All four values and not only the two above: an operator looking at a tenant that is waiting
   * to be erased should see that, which is exactly why {@link #setState} will refuse it.
   *
   * @param tenantId which tenant
   * @return the state name, or empty when there is no such tenant
   */
  Optional<String> stateOf(UUID tenantId);
}
