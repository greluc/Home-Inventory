/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request a {@code traceId}, in the log and in the error response (REQ-NFR-041/042).
 *
 * <p>This is the whole mechanism by which a user's report — "it said something went wrong" — becomes
 * a specific request an operator can look at. The id goes into the MDC, so the logging pattern
 * carries it on every line of the request, and {@link Problems} copies it into every problem
 * document.
 *
 * <h2>It steps aside when the deployment traces</h2>
 *
 * <p>Since 2026-09-21 tracing exists (REQ-NFR-044) and, when a collector is configured, the tracer
 * puts the <b>same MDC key</b> there from the span it started — the real W3C trace id of a trace
 * that spans core, broker, worker and plugin. Two mechanisms writing one key would give one request
 * two ids, so this one yields: it runs <i>after</i> Spring's observation filter and fills the key
 * only when that found nothing to put there.
 *
 * <p>That is not a degraded mode. Tracing is off unless an operator names a collector — the
 * requirement's own "inactive when unconfigured" — so on most deployments this filter is still the
 * whole of the mechanism, exactly as it was at stage 0. What it stopped being is the only one.
 *
 * <h2>An incoming traceparent is adopted</h2>
 *
 * <p>When one arrives and is well-formed, its trace-id is used, so a request that already crossed a
 * traced hop keeps one id end to end. It is a correlation identifier and never a trust decision —
 * nothing is authorised by it and nothing is looked up with it — but it is still checked against the
 * W3C shape before use, because it ends up in a log line and an unvalidated header is how a log line
 * gets a newline in it.
 */
@Component
// HIGHEST_PRECEDENCE + 2, and the two matter. Spring registers
// `ServerHttpObservationFilter` at HIGHEST_PRECEDENCE + 1 and opens the
// observation's scope around the rest of the chain, which is where a tracer puts
// its ids into the MDC. Sitting before it, this filter would write a random id
// that the tracer then shadowed for the length of the request and un-shadowed
// afterwards -- one request, two ids, and the one in the error document would
// not be the one in the log lines around it.
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class TraceIdFilter extends OncePerRequestFilter {

  /** The MDC key. The same one Micrometer Tracing's SLF4J integration uses. */
  static final String TRACE_ID = "traceId";

  /** W3C trace-context: 32 lowercase hex characters, and not the all-zero id. */
  private static final Pattern TRACE_ID_SHAPE = Pattern.compile("[0-9a-f]{32}");

  /** The all-zero trace-id, which the specification defines as invalid. */
  private static final String INVALID = "0".repeat(32);

  private static final SecureRandom RANDOM = new SecureRandom();

  @Override
  protected void doFilterInternal(
      @NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull FilterChain chain)
      throws ServletException, IOException {

    String traced = MDC.get(TRACE_ID);
    if (traced != null && !traced.isBlank()) {
      // A tracer is running and has already said what this request's id is.
      // Whatever this filter put there instead would be a second answer, and
      // removing it in the `finally` below would take the tracer's with it.
      chain.doFilter(request, response);
      return;
    }

    MDC.put(TRACE_ID, traceIdOf(request));
    try {
      chain.doFilter(request, response);
    } finally {
      // The thread goes back to a pool — a virtual one here, but the container
      // may still reuse the carrier's MDC — and a leftover id would label the
      // next request with the previous one's, which is worse than none.
      MDC.remove(TRACE_ID);
    }
  }

  /**
   * The id for this request.
   *
   * @param request the request
   * @return the trace-id from a well-formed {@code traceparent}, or a new random one
   */
  private static String traceIdOf(HttpServletRequest request) {
    String header = request.getHeader("traceparent");
    if (header != null) {
      // version "-" trace-id "-" parent-id "-" flags. Only the trace-id is used:
      // the parent-id identifies a span this application does not yet produce.
      String[] fields = header.split("-");
      if (fields.length >= 2
          && TRACE_ID_SHAPE.matcher(fields[1]).matches()
          && !INVALID.equals(fields[1])) {
        return fields[1];
      }
    }

    byte[] id = new byte[16];
    RANDOM.nextBytes(id);
    return HexFormat.of().formatHex(id);
  }
}
