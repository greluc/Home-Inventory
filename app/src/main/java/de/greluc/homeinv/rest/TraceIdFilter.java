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
 * <h2>Why not the tracing library</h2>
 *
 * <p>Because distributed tracing is stage 1 (REQ-NFR-044) and the {@code traceId} is stage 0. When
 * the OpenTelemetry agent arrives it populates the same MDC key from the same W3C id, so the log
 * format, the error documents and anything reading them stay as they are; this filter then becomes
 * a fallback for the case that requirement already names, "inactive when unconfigured".
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
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

  /** The MDC key. The same one OpenTelemetry's logging integration uses. */
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
