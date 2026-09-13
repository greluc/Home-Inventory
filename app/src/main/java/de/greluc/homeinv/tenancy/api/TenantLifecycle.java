/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tenancy.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Asking for a tenant to be erased, and changing one's mind (REQ-TEN-011, REQ-PRIV-005).
 *
 * <p>05 §5.9 describes the flow and this is its first half. The owner asks; the tenant becomes
 * {@code PENDING_DELETION} and stops answering at once while the data stays; a thirty-day grace
 * period runs, within which the request can be withdrawn. The second half — the blocks erasing
 * their share, reporting, and the certificate — happens after that period and is
 * {@link TenantErasure}'s.
 *
 * <p>Deleting a tenant is the only thing {@code OWNER} may do and {@code ADMIN} may not. Until this
 * existed the two held identical permissions and were separated only by REQ-TEN-010's rule about
 * granting ownership.
 */
public interface TenantLifecycle {

  /** What state a tenant is in. */
  enum State {
    /** Answering normally. */
    ACTIVE,

    /**
     * Stopped by the instance operator, data intact.
     *
     * <p>No endpoint sets this yet: suspending a tenant is an operator action and arrives with the
     * operator's own view. The state exists because 05 §5.9 and O26 describe it, and because a
     * check constraint that has to be widened later is a migration nobody plans for.
     */
    SUSPENDED,

    /** Asked to be erased, waiting out the grace period. */
    PENDING_DELETION,

    /**
     * Erased: every block has removed its share and a certificate was issued.
     *
     * <p>The row carrying this state is a tombstone — the id, the name the tenant had, and who
     * asked and when. Nobody reaches it: an erased tenant has no memberships left, so no session
     * can act for it, and {@link #state()} would have to be asked by somebody who is not there.
     *
     * <p>It exists because the row does. A finished erasure that left the state reading
     * {@code PENDING_DELETION} would be the one fact in that row nobody could check against the
     * rest of it.
     */
    ERASED
  }

  /**
   * What an erasure request produced.
   *
   * @param revocationToken the secret that withdraws it. Returned <b>once</b>: where
   *     {@code plugin-smtp} is installed it also goes out by mail, and where it is not — which a
   *     {@code minimal} installation is by design — this is the only way it reaches anybody
   * @param eraseAfter when the grace period runs out and the erasure begins
   */
  record DeletionRequest(String revocationToken, Instant eraseAfter) {}

  /**
   * The state of the tenant the session is acting for.
   *
   * @return its state
   * @throws de.greluc.homeinv.platform.NotFoundException when there is no such tenant
   */
  State state();

  /**
   * Asks for the tenant the session is acting for to be erased.
   *
   * <p>Answered {@code 202}: nothing is erased yet, and for thirty days nothing will be. Asking
   * twice returns the state as it stands rather than a second token — two live tokens would be two
   * ways to withdraw one request, and withdrawing with the one somebody remembers would leave the
   * other working.
   *
   * @param actor the owner asking
   * @return the token that withdraws it and the instant the erasure begins
   * @throws AlreadyPendingDeletionException when a request is already open
   */
  DeletionRequest requestDeletion(UUID actor);

  /**
   * Withdraws a request, by its token.
   *
   * <p>Runs without a tenant context and without a session: the token is what identifies the
   * tenant, through the {@code SECURITY DEFINER} lookup of 07 §7.5. A link in a mail has to work
   * for somebody who cannot sign in, because signing in is exactly what the pending deletion
   * stopped.
   *
   * @param revocationToken the secret from the request
   * @return the tenant that is active again
   * @throws RevocationUnusableException when the token is unknown, already used, or the tenant is
   *     past its grace period
   */
  UUID revokeDeletion(String revocationToken);
}
