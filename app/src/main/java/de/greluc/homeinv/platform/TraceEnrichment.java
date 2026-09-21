/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.platform;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Puts the tenant and the actor on every span, and nothing else (REQ-NFR-044, 13 §13.4).
 *
 * <h2>Why these two and no more</h2>
 *
 * <p>A trace is read when something went wrong, and the first two questions are always <i>whose
 * data</i> and <i>who was doing it</i>. Everything past that — an item's name, a location, a
 * search term — is <b>domain data</b>, and a span is the wrong place for it: spans leave the
 * deployment for the operator's collector, are kept far longer than a log line and are not covered
 * by the erasure of {@code REQ-PRIV-006}. 13 §13.4 names exactly {@code tenantId}, {@code actorId}
 * and the plugin id, and this is the whole of the first two.
 *
 * <h2>High cardinality, which here is a category and not a complaint</h2>
 *
 * <p>Micrometer splits key values in two: the low-cardinality ones become <b>meter tags</b> and the
 * high-cardinality ones reach spans only. A tenant id is a per-installation unbounded set and an
 * actor id is worse, so a low-cardinality key value of either would turn every HTTP metric into one
 * time series per user — the classic way to take a monitoring system down with its own data. Here
 * the distinction does exactly what it is for.
 *
 * <h2>It runs whether or not anything is traced</h2>
 *
 * <p>An {@link ObservationFilter} is applied when an observation stops, before the handlers see it,
 * and with tracing off there is no handler that reads high-cardinality values — so this computes
 * two thread-local reads and throws them away. That is cheaper than making the bean conditional and
 * then discovering that an operator who wired their own exporter has traces without a tenant on
 * them.
 */
@Component
public class TraceEnrichment implements ObservationFilter {

  /** The attribute naming the tenant, spelled as the MDC and the log lines spell it. */
  static final String TENANT_ID = "tenantId";

  /** The attribute naming the person, spelled as the MDC and the log lines spell it. */
  static final String ACTOR_ID = "actorId";

  @Override
  public Observation.Context map(Observation.Context context) {
    UUID tenant = TenantContext.current().orElse(null);
    UUID actor = CallerContext.current().map(CallerContext.Caller::userId).orElse(null);
    if (tenant == null && actor == null) {
      // Every observation that is not a request: a scheduled relay run, a
      // startup probe. Nothing to say about either, and saying "unknown" would
      // be a value somebody eventually filters on.
      return context;
    }
    if (tenant != null) {
      context.addHighCardinalityKeyValue(KeyValue.of(TENANT_ID, tenant.toString()));
    }
    if (actor != null) {
      context.addHighCardinalityKeyValue(KeyValue.of(ACTOR_ID, actor.toString()));
    }
    return context;
  }
}
