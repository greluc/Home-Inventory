/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.audit.api;

import de.greluc.homeinv.platform.Page;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Who did what, when, and from where (REQ-SEC-068…071, ADR-0031).
 *
 * <h2>Not the revision log beside it</h2>
 *
 * <p>{@link RevisionLog} answers "what did this item look like at version 4, and can I have it
 * back". This answers "who changed it". One is domain history a person restores from; the other is
 * evidence. That is why one keeps a snapshot per entity and this keeps a hash chain per tenant.
 *
 * <h2>Written inside the caller's transaction</h2>
 *
 * <p>{@link #record} demands one. An audit entry that could be written separately is a log with a
 * gap wherever the second write failed — and a gap reads as "nothing happened", which is the one
 * thing it must never say. It follows that a rolled-back operation leaves no entry, which is
 * correct: nothing happened.
 *
 * <h2>Append-only, by privilege</h2>
 *
 * <p>{@code homeinv_app} holds {@code INSERT} and {@code SELECT} on {@code audit.audit_entry} and
 * nothing else, so an update or a delete is refused by the database rather than by a rule somebody
 * has to remember (REQ-SEC-069). Retention runs under {@code homeinv_housekeeping}, which is a
 * different role for exactly that reason (ADR-0046).
 */
public interface AuditLog {

  /**
   * Records one mutating action.
   *
   * <p>Chained: the entry carries the hash of this tenant's previous one, under a per-tenant
   * advisory lock held for the insert. Tenants never block one another, and a transaction writing
   * several entries takes the lock once (REQ-SEC-070).
   *
   * @param entry what happened
   * @return the sequence number it was given, this tenant's own and starting at one
   * @throws IllegalStateException when called outside a transaction, or with no tenant context
   */
  long record(NewEntry entry);

  /**
   * Records one mutating action, taking who and from where from the ambient {@link AuditTrail}.
   *
   * <p>The form a block uses. It writes what it knows — what happened, to what, and what changed —
   * and the six fields about the actor and the request come from the boundary that established
   * them. A service that took an address as a parameter would carry the web layer into the domain;
   * one that read a request would be a service that cannot be called from a queue.
   *
   * <p>Marks the trail as recorded, so the boundary does not write a second, plainer entry for the
   * same action.
   *
   * @param action what was done, as the block names it: {@code item.created}, {@code member.removed}
   * @param resourceType what kind of thing it was done to
   * @param resourceId which one, or {@code null} where the action is not about a single row
   * @param diff field to before-and-after, <b>after redaction</b> (REQ-SEC-027)
   * @return the sequence number it was given
   * @throws IllegalStateException when no origin has been established, which means a mutating path
   *     runs outside every boundary that knows who is acting
   */
  long record(String action, String resourceType, UUID resourceId, Map<String, Object> diff);

  /**
   * What one account did in a period (REQ-SEC-071).
   *
   * <p>The question an incident asks first, and the reason the actor index exists. Answered per
   * tenant, under the caller's own context: an operator asking across tenants is impersonating or
   * reading the database directly, both of which are their own audited acts.
   *
   * @param actorId whose actions, or {@code null} for every actor in the period
   * @param from the start of the period, inclusive
   * @param to the end, exclusive
   * @param cursor an opaque position from a previous page, or {@code null} for the first
   * @param limit how many at most; capped at 200 (REQ-NFR-010)
   * @return one page, newest first
   */
  Page<AuditView> actions(UUID actorId, Instant from, Instant to, String cursor, int limit);

  /**
   * One action, as a block reports it.
   *
   * @param actorKind who kind of actor did it. A plugin write carries {@link ActorKind#PLUGIN} and
   *     the plugin's id, which is what makes it distinguishable from a person's (REQ-PLG-011)
   * @param actorId the person or the service account, and {@code null} for a plugin or the system
   * @param actorLabel the plugin id or the system task's name, and {@code null} for a person
   * @param action what was done, as the block names it: {@code item.created},
   *     {@code member.removed}
   * @param resourceType what kind of thing it was done to
   * @param resourceId which one, or {@code null} where the action is not about a single row
   * @param diff field to before-and-after. <b>Built after redaction</b>: a {@code sensitive} value
   *     must not reach here, because an audit log that recorded what a role may not read would be a
   *     way to read it (REQ-SEC-027)
   * @param ip where the request came from, or {@code null} for a system task
   * @param client what made it — a user agent, a service account's name
   * @param correlationId the trace this belongs to, so an entry and the log lines around it can be
   *     put side by side
   */
  record NewEntry(
      ActorKind actorKind,
      UUID actorId,
      String actorLabel,
      String action,
      String resourceType,
      UUID resourceId,
      Map<String, Object> diff,
      String ip,
      String client,
      String correlationId) {}

  /**
   * One recorded action, as it is read back.
   *
   * @param seq this tenant's sequence number
   * @param occurredAt when
   * @param actorKind who kind of actor
   * @param actorId the person or service account, or {@code null}
   * @param actorLabel the plugin or system task, or {@code null}
   * @param action what was done
   * @param resourceType what kind of thing
   * @param resourceId which one, or {@code null}
   * @param diff what changed
   * @param ip where from, or {@code null} once the retention run has removed it (REQ-PRIV-006)
   * @param client what made it
   * @param correlationId the trace
   */
  record AuditView(
      long seq,
      Instant occurredAt,
      ActorKind actorKind,
      UUID actorId,
      String actorLabel,
      String action,
      String resourceType,
      UUID resourceId,
      String diff,
      String ip,
      String client,
      String correlationId) {}

  /** What kind of thing acted. */
  enum ActorKind {
    /** A person, signed in. */
    USER,
    /** A machine credential belonging to a tenant (REQ-AUTH-010). */
    SERVICE_ACCOUNT,
    /**
     * A plugin, acting through the core's own API.
     *
     * <p>Its id is in the label and there is no {@code actorId}, which is what REQ-PLG-011 and
     * REQ-SEC-073 mean by distinguishable: no reader has to know which ids are people.
     */
    PLUGIN,
    /** The system itself — a scheduled task, a migration, a retention run. */
    SYSTEM
  }
}
