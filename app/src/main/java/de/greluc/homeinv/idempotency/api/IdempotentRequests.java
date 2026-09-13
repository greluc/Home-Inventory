/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.idempotency.api;

import java.util.Optional;
import java.util.UUID;

/**
 * {@code Idempotency-Key}: the same request twice, the same answer once (REQ-API-005).
 *
 * <h2>Why this is a block and not a filter</h2>
 *
 * <p>ADR-0009 decided the record and the entity it protects share one {@code COMMIT} — "two stores
 * cannot be made consistent by hoping, and here they need not be". That is only true if the record
 * is written inside the transaction that writes the entity, which means it is written by the
 * <em>use case</em> and not by something wrapped around the request. So this is a port a service
 * calls, twice: once to ask whether the work has already been done, once to record what it
 * answered.
 *
 * <h2>What it protects against, and what it does not</h2>
 *
 * <p>It is for a client that sent a request and never saw the answer — the mobile case REQ-API-005
 * was written for, where the network drops between the write and the acknowledgement. It is not a
 * lock: two <em>different</em> requests racing are not its problem, and {@code If-Match} is what
 * answers those (REQ-API-004).
 */
public interface IdempotentRequests {

  /**
   * What this key already answered, if it has.
   *
   * <p>The hash is checked as well as the key. A repeat carrying the same key and a different body
   * is a client bug — one key spent twice — and is refused rather than answered with the earlier
   * result, which would be a request that silently did nothing.
   *
   * <p>A record past its 24 hours is treated as absent. The expiry is therefore a property of the
   * read and not of a sweep that may or may not have run — which is what makes "valid for 24 h"
   * true at the moment it matters rather than at the moment a job last succeeded.
   *
   * @param operation which endpoint the key was spent on, so one key cannot be reused across two
   * @param key the {@code Idempotency-Key} header, verbatim
   * @param requestHash the SHA-256 of the request body, lower-case hex
   * @return the JSON this key answered before, or empty when it is unspent or has expired
   * @throws IdempotencyKeyConflictException when the key is spent on a different body or operation
   */
  Optional<String> replay(String operation, String key, String requestHash);

  /**
   * Records what this key answered, inside the caller's transaction.
   *
   * <p>Called after the work and before the commit. That ordering is the requirement: a record
   * written first could survive work that failed, and one written after the commit could be lost
   * while the entity stayed — which is the duplicate REQ-API-005 exists to prevent.
   *
   * @param operation which endpoint the key was spent on
   * @param key the {@code Idempotency-Key} header, verbatim
   * @param requestHash the SHA-256 of the request body, lower-case hex
   * @param response the answer, serialised as JSON
   * @param actor the authenticated user
   */
  void remember(String operation, String key, String requestHash, String response, UUID actor);

  /**
   * Removes this tenant's records that are past their 24 hours.
   *
   * <p><b>This tenant's</b>, and that is not a shortcut. An instance-wide sweep cannot work here:
   * PostgreSQL applies SELECT policies to a {@code DELETE} whose {@code WHERE} names a column, so
   * one running without a tenant context matches nothing and reports success. Widening it would
   * mean a policy letting one tenant read another's stored responses, which is a worse trade than
   * a row that lives a little longer.
   *
   * <p>So it is called where a context already exists — after a key is spent — and the rest goes
   * with the tenant when the tenant goes (REQ-TEN-011). Nothing depends on it having run: a record
   * past its day is ignored by {@link #replay} whether or not it is still a row.
   *
   * @return how many were removed
   */
  int expireForThisTenant();
}
