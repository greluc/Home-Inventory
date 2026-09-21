/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.platform;

import java.util.UUID;

/**
 * A domain event that belongs to one tenant, and says what it is.
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
 * <p>Two consumers read every event through this interface and nothing else:
 *
 * <ul>
 *   <li>{@code eventstream} tells a connected browser that something of a given kind changed in its
 *       tenant, so an open view can refresh itself (REQ-API-011);
 *   <li>{@code notification} delivers the type, the moment and the subject's id to the webhook
 *       targets that asked for that type (REQ-API-010).
 * </ul>
 *
 * <p>Neither forwards the event's <b>contents</b>. See those blocks for why.
 *
 * <h2>Why the name is declared and not derived</h2>
 *
 * <p>{@link #eventType()} is a string a receiver outside this deployment stores in its own
 * configuration, so it outlives the class that produced it: a rename, a package move or a
 * refactoring must not change it. Deriving it from the class name — which is what {@code
 * eventstream} did until 2026-09-21 — makes every one of those a silent breaking change for
 * somebody else's integration, and a fallback of {@code "change"} for a class the heuristic did not
 * recognise. The registry in {@code docs/reference/event-types.yaml} is the published list, and
 * {@code EventTypeRegistryTest} holds the two together.
 */
public interface TenantScopedEvent {

  /**
   * Whose data changed.
   *
   * @return the tenant, never null
   */
  UUID tenantId();

  /**
   * What happened, as the stable name outside this deployment knows it.
   *
   * <p>An {@link EventType}, not a string: the name is stored in a subscription and branched on
   * by a receiver outside this deployment, so a typo must not compile. {@link EventType#noun()} —
   * everything before the dot — is the coarse kind a live stream sends (REQ-API-011), so a new
   * event of a known noun needs no second decision.
   *
   * <p><b>Never changed once published.</b> A webhook target names these in its subscription and a
   * receiver branches on them; a rename is a breaking change to somebody else's system, which is
   * why {@code docs/reference/event-types.yaml} carries the list and a test refuses one that is not
   * in it.
   *
   * @return the type, never null
   */
  EventType eventType();

  /**
   * What it happened <b>to</b>.
   *
   * <p>The thing a receiver would go and read: the item that was created, the location that was
   * moved, the item a tag was put on. Not the tag — a receiver told that a tag changed wants to
   * know which thing wears it now.
   *
   * @return the subject's id, never null
   */
  UUID subjectId();
}
