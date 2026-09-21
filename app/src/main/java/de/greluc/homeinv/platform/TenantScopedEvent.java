/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.platform;

import java.util.UUID;

/**
 * A domain event that belongs to one tenant.
 *
 * <p>Every event this application publishes already carries a {@code tenantId} — it has to, because
 * a consumer reading one has no ambient context to fall back on. This interface says so in the type
 * system, which is what lets a listener be written for <b>all</b> of them rather than one per event.
 *
 * <h2>Why it is here and not in a block</h2>
 *
 * <p>{@code platform} is the shared kernel every block may depend on, and the events themselves
 * live in the published {@code api} package of the block that raises them. A marker in any one
 * block would make every other block depend on that one for a reason that has nothing to do with
 * it.
 *
 * <h2>What it is for</h2>
 *
 * <p>{@code eventstream} listens for this and nothing else: it tells a connected browser that
 * something of a given kind changed in its tenant, so an open view can refresh itself
 * (REQ-API-011). It never forwards the event's contents — see that block for why.
 */
public interface TenantScopedEvent {

  /**
   * Whose data changed.
   *
   * @return the tenant, never null
   */
  UUID tenantId();
}
