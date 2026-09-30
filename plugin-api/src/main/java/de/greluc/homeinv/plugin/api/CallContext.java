/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: Apache-2.0
 */
package de.greluc.homeinv.plugin.api;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Who a call is for, and what authorised it.
 *
 * <p>Every port method takes one. The tenant is the first argument of the plugin contract and not
 * an afterthought: a plugin is installed once for the instance and granted per tenant (09 §9.4), so
 * "which tenant" is the question that decides whether a call is allowed at all. The core has already
 * answered it — the capability was checked before the call left — and the plugin gets the answer so
 * that it can keep its own per-tenant state apart.
 *
 * <h2>Two scopes, and the tenant is absent in exactly one of them</h2>
 *
 * <p>Almost every call is {@link Scope#TENANT}: one tenant granted the plugin a capability and the
 * call is made under that grant. {@link Scope#INSTANCE} exists for the obligations the instance owes
 * an <b>account</b> rather than a tenant — today the security notifications of {@code REQ-NOTI-004},
 * which cannot be switched off by a user or a tenant administrator and must still reach somebody
 * who is a member of no tenant at all. There the instance operator is the grantor, there is no
 * tenant to name, and {@link #tenantId()} is {@code null} ([ADR-0066]).
 *
 * <p>A plugin that only serves tenants does not have to carry the distinction: call {@link
 * #requireTenantId()} and an instance call fails loudly at the boundary instead of quietly treating
 * "no tenant" as some tenant.
 *
 * <p><b>The tenant here is not an authorisation.</b> A plugin that trusts this field to decide what
 * it may reach is trusting its caller, and a plugin calling <i>back</i> into the core is
 * re-authorised there on every call against the token it carries, never against a field it filled
 * in itself (09 §9.6, REQ-SEC-057). On an instance call there is no tenant context at all, so a
 * callback reaches no tenant's data — the scope grants the plugin nothing beyond the one call.
 *
 * @param tenantId the tenant the call is made for, or {@code null} — and {@code null} exactly when
 *     the scope is {@link Scope#INSTANCE}
 * @param traceId the W3C trace parent of the call, so a plugin's own log lines join the core's
 *     trace rather than sitting beside it. Empty when tracing is off
 * @param language the caller's language as an IETF tag ({@code de}, {@code en}), for messages a
 *     plugin composes itself. Empty when the call has no human reader
 * @param deadlineMillis how long the caller will wait, from the moment the call was made. A plugin
 *     that cannot finish in time should stop rather than answer late: the caller has gone
 *     (REQ-PLG-007). Zero means the caller named none here, which is what the core passes:
 *     what actually bounds a call is the manifest's {@code timeoutSeconds}, applied to the
 *     channel by the envelope (ADR-0065). <i>This said "which the core never does" until
 *     2026-09-21, while every caller in the core passed zero.</i>
 * @param settings what <b>this tenant</b> configured for this plugin, by the keys the manifest
 *     declares, with secrets opened (ADR-0073, REQ-PLG-017). Never the operator's configuration of
 *     the plugin container — the SMTP host, the S3 keys, the OIDC client secret are the
 *     container's own environment and never pass through the core. Empty on an instance call and
 *     whenever the manifest declares no setting, never {@code null}
 */
public record CallContext(
    UUID tenantId,
    String traceId,
    String language,
    long deadlineMillis,
    Map<String, String> settings) {

  /**
   * Normalises the optional text and checks the one invariant a deadline has.
   *
   * <p>The tenant is deliberately <b>not</b> required here: its absence is what says the call is an
   * instance call, and a record cannot express "one of two shapes" more directly than that. What
   * keeps the absence honest is that only {@link #forInstance} produces it and that every caller
   * that needs a tenant asks for it through {@link #requireTenantId()}.
   *
   * @throws IllegalArgumentException when the deadline is negative
   */
  public CallContext {
    traceId = traceId == null ? "" : traceId;
    language = language == null ? "" : language;
    settings = settings == null ? Map.of() : Map.copyOf(settings);
    if (deadlineMillis < 0) {
      throw new IllegalArgumentException("A deadline in the past is not a deadline");
    }
  }

  /**
   * A context with no settings, which is every call made before a plugin declares one.
   *
   * @param tenantId the tenant, or {@code null} for an instance call
   * @param traceId the W3C trace parent, or {@code null}
   * @param language the recipient's language, or {@code null}
   * @param deadlineMillis how long the caller will wait
   */
  public CallContext(UUID tenantId, String traceId, String language, long deadlineMillis) {
    this(tenantId, traceId, language, deadlineMillis, Map.of());
  }

  /**
   * The same call, carrying what the tenant configured.
   *
   * <p>Filled in one place — the envelope every plugin call goes through — so that no caller has
   * to remember to, and so that a plugin's settings cannot reach a call made for a different
   * tenant.
   *
   * @param settings the resolved settings
   * @return a copy carrying them
   */
  public CallContext withSettings(Map<String, String> settings) {
    return new CallContext(tenantId, traceId, language, deadlineMillis, settings);
  }

  /**
   * The same call, carrying the trace it belongs to.
   *
   * <p>Filled in the same one place as the settings, and for the same reason: a caller that had to
   * remember it would be a caller who sometimes did not, and a trace with a hole in it where the
   * plugin hop should be is worse than an honest absence. The core passes the W3C
   * {@code traceparent} of the span the call is made from; {@code ""} says nothing is being traced,
   * which is what every deployment without a collector reports.
   *
   * @param traceParent the W3C trace parent, or {@code null} for none
   * @return a copy carrying it
   */
  public CallContext withTraceId(String traceParent) {
    return new CallContext(tenantId, traceParent, language, deadlineMillis, settings);
  }

  /** What authorised a call, and therefore what it may touch. */
  public enum Scope {
    /** One tenant granted the capability, and {@link CallContext#tenantId()} names it. */
    TENANT,

    /**
     * The instance operator granted the capability for the deployment itself, and there is no
     * tenant to name ([ADR-0066]).
     */
    INSTANCE
  }

  /**
   * Which of the two shapes this is, derived from the tenant rather than stored beside it.
   *
   * <p>Derived on purpose: two fields that must agree are two fields that eventually do not.
   *
   * @return {@link Scope#INSTANCE} when there is no tenant, {@link Scope#TENANT} otherwise
   */
  public Scope scope() {
    return tenantId == null ? Scope.INSTANCE : Scope.TENANT;
  }

  /**
   * The tenant, for a caller that has nothing sensible to do without one.
   *
   * @return the tenant this call is made for
   * @throws IllegalStateException when this is an instance call — which is the point: a port that
   *     stores per-tenant state should fail here rather than invent a tenant
   */
  public UUID requireTenantId() {
    if (tenantId == null) {
      throw new IllegalStateException(
          "This is an instance call and has no tenant (ADR-0066). A port that needs one should"
              + " refuse the call rather than choose a tenant for it.");
    }
    return tenantId;
  }

  /**
   * The tenant, for a caller that handles both shapes.
   *
   * @return the tenant, or empty on an instance call
   */
  public Optional<UUID> optionalTenantId() {
    return Optional.ofNullable(tenantId);
  }

  /**
   * A context for a tenant, with no trace, no language and no deadline.
   *
   * <p>For tests and for in-process callers that have none of the three. The core always fills all
   * four, because a call without a deadline is the one that never comes back.
   *
   * @param tenantId the tenant, never {@code null}
   * @return the context
   * @throws NullPointerException when the tenant is absent — use {@link #forInstance} to say so
   *     deliberately
   */
  public static CallContext of(UUID tenantId) {
    Objects.requireNonNull(
        tenantId, "A tenant call is always for a tenant; use forInstance() for an instance call");
    return new CallContext(tenantId, "", "", 0L);
  }

  /**
   * A context for the instance itself, which has no tenant ([ADR-0066]).
   *
   * <p>The core builds one only where an instance-level capability grant authorises it, and only
   * for an obligation the instance owes an account rather than a tenant.
   *
   * @param traceId the W3C trace parent, or {@code null} for none
   * @param language the recipient's language as an IETF tag, or {@code null} for none
   * @param deadlineMillis how long the caller will wait
   * @return the context
   */
  public static CallContext forInstance(String traceId, String language, long deadlineMillis) {
    return new CallContext(null, traceId, language, deadlineMillis);
  }
}
