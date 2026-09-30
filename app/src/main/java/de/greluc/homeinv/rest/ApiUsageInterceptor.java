/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Counts what each client asks of each endpoint and version (REQ-API-009).
 *
 * <h2>Why it exists</h2>
 *
 * <p>ADR-0011 puts it plainly: without this measurement "a shutdown is a leap in the dark". An API
 * version is retired when nobody is using it, and 08 §8 makes that a rule rather than a judgement —
 * shutdown happens only when usage is zero or the deprecation period has elapsed. This is the
 * number that sentence refers to.
 *
 * <h2>Why every tag is bounded</h2>
 *
 * <p>A Prometheus series exists per combination of tag values, so an unbounded tag is an outage
 * waiting for the right request. Each of the four is bounded by construction:
 *
 * <ul>
 *   <li><b>endpoint</b> — the route <i>template</i> ({@code /api/v1/items/{id}}), never the path,
 *       so a million item ids are one series and not a million;
 *   <li><b>version</b> — the {@code v1} out of the path, and {@code none} for the handful of
 *       endpoints outside the versioned API;
 *   <li><b>client</b> — the <b>product</b> out of {@code X-Home-Inv-Client}, matched against a
 *       known set. Anything unrecognised is {@code other}, which is what keeps a header somebody
 *       else controls from inventing series. The <i>version</i> the client sends is deliberately
 *       <b>not</b> a tag: it is what a deprecation notice is addressed to, not what a shutdown
 *       decision needs, and every release of every client would otherwise be its own series;
 *   <li><b>outcome</b> — {@code success}, {@code client-error} or {@code server-error}, three
 *       values rather than every status code.
 * </ul>
 *
 * <h2>Why a header and not `User-Agent`</h2>
 *
 * <p>ADR-0011 says every client sends a {@code User-Agent} with product and version, and the web
 * PWA cannot: a browser sets that header itself and a page cannot override it on {@code fetch}. So
 * a client names itself in {@code X-Home-Inv-Client}, in the shape {@code product/version} the ADR
 * asked of the other header. Decided with the owner on 2026-09-20.
 *
 * <p>This is <b>not</b> the per-tenant API-call counter of REQ-TEN-009: that one is a quota,
 * counted in Valkey, enforced, and carries a tenant. This one is instance-wide telemetry, carries
 * no tenant at all, and enforces nothing.
 */
@Component
@RequiredArgsConstructor
public class ApiUsageInterceptor implements HandlerInterceptor {

  /** The header a client names itself in (08 §8). */
  public static final String CLIENT_HEADER = "X-Home-Inv-Client";

  /** The metric 08 §8's shutdown rule reads. */
  private static final String METRIC = "homeinv.api.requests";

  /**
   * The products this deployment knows.
   *
   * <p>A closed set, because the tag comes from a header a caller controls: anything else is {@code
   * other}, and no request can add a series. Growing it is a deliberate change here, which is the
   * point — a new client being invisible until somebody notices is a better failure than a
   * cardinality explosion nobody can undo.
   */
  private static final Set<String> KNOWN =
      Set.of("web", "android", "ios", "desktop", "cli", "plugin");

  /** The version segment of a path under the versioned API. */
  private static final Pattern VERSION = Pattern.compile("^/api/(v\\d+)(/|$)");

  /** What a client may send: a product, optionally with its version after a slash. */
  private static final Pattern CLIENT = Pattern.compile("^([A-Za-z][A-Za-z0-9-]{0,31})(?:/.*)?$");

  private final MeterRegistry meters;

  @Override
  public void afterCompletion(
      @NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull Object handler,
      Exception failure) {
    meters
        .counter(
            METRIC,
            "endpoint",
            endpointOf(request),
            "version",
            versionOf(request),
            "client",
            clientOf(request),
            "outcome",
            outcomeOf(response))
        .increment();
  }

  /**
   * The route template the request matched.
   *
   * <p>The template and never the path: {@code /api/v1/items/{id}} is one series where the path
   * would be one per item. A request that matched no template at all — a 404 on a path nothing
   * serves — is counted as {@code unmatched}, which is a real thing worth seeing and is one series
   * rather than one per URL somebody tried.
   *
   * @param request the request
   * @return the template, or {@code unmatched}
   */
  private static String endpointOf(HttpServletRequest request) {
    Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
    return pattern == null ? "unmatched" : pattern.toString();
  }

  /**
   * The API version out of the path.
   *
   * @param request the request
   * @return {@code v1}, or {@code none} for an endpoint outside the versioned API
   */
  private static String versionOf(HttpServletRequest request) {
    java.util.regex.Matcher matcher = VERSION.matcher(request.getRequestURI());
    return matcher.find() ? matcher.group(1) : "none";
  }

  /**
   * Which product made the call.
   *
   * @param request the request
   * @return a known product, or {@code other} — including when the header is absent, which is what
   *     a script or a curl call looks like
   */
  private static String clientOf(HttpServletRequest request) {
    String header = request.getHeader(CLIENT_HEADER);
    if (header == null || header.isBlank()) {
      return "other";
    }
    java.util.regex.Matcher matcher = CLIENT.matcher(header.strip());
    if (!matcher.matches()) {
      return "other";
    }
    String product = matcher.group(1).toLowerCase(Locale.ROOT);
    return KNOWN.contains(product) ? product : "other";
  }

  /**
   * How it went, in three values.
   *
   * @param response the response
   * @return {@code success}, {@code client-error} or {@code server-error}
   */
  private static String outcomeOf(HttpServletResponse response) {
    int status = response.getStatus();
    if (status >= 500) {
      return "server-error";
    }
    return status >= 400 ? "client-error" : "success";
  }
}
