/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: Apache-2.0
 */
package de.greluc.homeinv.plugin.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Who a call is for, and what it belongs to.
 *
 * <p>Every port method takes one. The tenant is the first argument of the plugin contract and not
 * an afterthought: a plugin is installed once for the instance and granted per tenant (09 §9.4), so
 * "which tenant" is the question that decides whether a call is allowed at all. The core has already
 * answered it — the capability was checked before the call left — and the plugin gets the answer so
 * that it can keep its own per-tenant state apart.
 *
 * <p><b>The tenant here is not an authorisation.</b> A plugin that trusts this field to decide what
 * it may reach is trusting its caller, and a plugin calling <i>back</i> into the core is
 * re-authorised there on every call against the token it carries, never against a field it filled
 * in itself (09 §9.6, REQ-SEC-057).
 *
 * @param tenantId the tenant the call is made for. Never {@code null}: a call for no tenant is a
 *     call the capability model cannot answer
 * @param traceId the W3C trace parent of the call, so a plugin's own log lines join the core's
 *     trace rather than sitting beside it. Empty when tracing is off
 * @param language the caller's language as an IETF tag ({@code de}, {@code en}), for messages a
 *     plugin composes itself. Empty when the call has no human reader
 * @param deadlineMillis how long the caller will wait, from the moment the call was made. A plugin
 *     that cannot finish in time should stop rather than answer late: the caller has gone
 *     (REQ-PLG-007). Zero means the caller set no deadline, which the core never does
 */
public record CallContext(UUID tenantId, String traceId, String language, long deadlineMillis) {

  /**
   * Checks the invariants the whole contract rests on.
   *
   * @throws NullPointerException when the tenant is absent
   * @throws IllegalArgumentException when the deadline is negative
   */
  public CallContext {
    Objects.requireNonNull(tenantId, "A plugin call is always for a tenant (09 §9.4)");
    traceId = traceId == null ? "" : traceId;
    language = language == null ? "" : language;
    if (deadlineMillis < 0) {
      throw new IllegalArgumentException("A deadline in the past is not a deadline");
    }
  }

  /**
   * A context for a tenant, with no trace, no language and no deadline.
   *
   * <p>For tests and for in-process callers that have none of the three. The core always fills all
   * four, because a call without a deadline is the one that never comes back.
   *
   * @param tenantId the tenant
   * @return the context
   */
  public static CallContext of(UUID tenantId) {
    return new CallContext(tenantId, "", "", 0L);
  }
}
