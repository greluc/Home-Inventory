/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.platform;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The current span, in the form a hop out of this process needs it (REQ-NFR-044, 13 §13.4).
 *
 * <p>Two hops leave the core and are not instrumented by a library: the gRPC call to a plugin, and
 * the {@code trace_id} field the plugin contract carries in the call payload. Both need the same
 * thing — the context of whatever span is current — and both get it here rather than each reaching
 * for the tracer in its own way.
 *
 * <h2>Everything here answers "nothing" when tracing is off</h2>
 *
 * <p>Which is the normal case: tracing is configured by naming a collector, and without one
 * {@link TracingEnvironment} sets {@code management.opentelemetry.enabled=false}. The plugin
 * contract already says {@code trace_id} is <i>"empty when tracing is off"</i>, so the empty answer
 * is a documented value rather than a failure.
 *
 * <p>The absence is structural rather than checked here: without a collector,
 * {@link TracingEnvironment} excludes the auto-configurations that build the bridge, so there is no
 * {@link Propagator} bean to ask and the loop below stops at the first line. That is why this class
 * asks for both providers rather than requiring them — and why a plugin is handed an empty field
 * rather than a well-formed identifier pointing at a trace that exists nowhere, which is the kind
 * of contract violation somebody spends an afternoon on.
 *
 * <h2>W3C, because the contract names it</h2>
 *
 * <p>{@code home_inv.plugin.v1.CallContext.trace_id} is specified as a W3C {@code traceparent}, so
 * {@code management.tracing.propagation.produce} is pinned to {@code W3C} in
 * {@code application.yaml}. Changing that would not merely alter a header — it would make the
 * published contract false, because the field would then carry a B3 identifier under a name that
 * promises otherwise.
 */
@Component
public class CurrentTrace {

  /** The W3C header name, which is also the key the propagator writes it under. */
  public static final String TRACEPARENT = "traceparent";

  /** Asked rather than injected: which of these exist depends on the configuration. */
  private final ObjectProvider<Tracer> tracer;

  /** Likewise: Boot defines one only alongside a tracer. */
  private final ObjectProvider<Propagator> propagator;

  /**
   * Binds the tracer and the propagation format, either of which may not exist.
   *
   * @param tracer the tracer, when one exists
   * @param propagator the propagation format, when one exists
   */
  public CurrentTrace(ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
    this.tracer = tracer;
    this.propagator = propagator;
  }

  /**
   * The {@code traceparent} of the current span.
   *
   * @return the header value, or {@code ""} when nothing is being traced
   */
  public String traceparent() {
    Map<String, String> carrier = new HashMap<>(2);
    inject(carrier, Map::put);
    String value = carrier.get(TRACEPARENT);
    return value == null ? "" : value;
  }

  /**
   * Writes the propagation fields of the current span into a carrier.
   *
   * <p>Through the {@link Propagator} rather than by formatting a string, so that what goes on the
   * wire is what the deployment is configured to produce — including {@code tracestate} and
   * baggage, which a hand-written {@code traceparent} would silently drop.
   *
   * @param carrier whatever carries headers — a gRPC {@code Metadata}, a map
   * @param setter how to put one field into it
   * @param <C> the carrier's type
   */
  public <C> void inject(C carrier, Propagator.Setter<C> setter) {
    Tracer current = tracer.getIfAvailable();
    Propagator format = propagator.getIfAvailable();
    if (current == null || format == null) {
      return;
    }
    Span span = current.currentSpan();
    if (span == null) {
      return;
    }
    format.inject(span.context(), carrier, setter);
  }
}
