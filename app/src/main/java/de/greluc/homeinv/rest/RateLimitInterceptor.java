/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import de.greluc.homeinv.platform.CallerContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Applies the request limits of {@code REQ-SEC-064} and tells every caller where they stand.
 *
 * <h2>Not the quota and not the login throttle</h2>
 *
 * <p>Three things count requests in this application and each answers a different question:
 *
 * <ul>
 *   <li><b>this</b> — too fast. {@code 429}, {@code Retry-After} a few seconds, and the window
 *       turns over on its own;
 *   <li>{@link ApiCallQuotaInterceptor} — the month's allowance is spent. {@code 403}, and waiting
 *       a moment changes nothing ({@code REQ-TEN-009});
 *   <li>the login throttle — too many <b>failures</b> in a row, with a delay that doubles
 *       ({@code REQ-SEC-012}). It counts failures; this counts requests.
 * </ul>
 *
 * <h2>Before the permission check, deliberately</h2>
 *
 * <p>The opposite of where the quota sits, and for the opposite reason. A call the caller was never
 * allowed to make should not come out of their monthly allowance — but it <b>did</b> cost the
 * instance a request, and a flood of refused calls is exactly the flood worth stopping. So the
 * limit is applied to every request that reaches a controller, whether or not it is then allowed.
 *
 * <h2>Every response says where the caller stands</h2>
 *
 * <p>{@code RateLimit} and {@code RateLimit-Policy}, in the form of the IETF draft the CORS
 * configuration already exposes, carrying the <b>tightest</b> of the applicable scopes — the one
 * that will refuse first, which is the only one worth reporting. A client that reads them never
 * has to meet a {@code 429} to find out there is a limit.
 */
@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

  /** Where this application's own endpoints live; everything else is exempt. */
  private static final String ACCESS_LAYER = "de.greluc.homeinv.rest";

  /** The authentication endpoints, which carry the stricter limit. */
  private static final String AUTH_PREFIX = "/api/v1/auth";

  private final RequestCounters counters;
  private final RateLimitProperties limits;

  @Override
  public boolean preHandle(
      @NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull Object handler) {

    if (!(handler instanceof HandlerMethod method)
        || !method.getBeanType().getPackageName().startsWith(ACCESS_LAYER)) {
      // The actuator, the error dispatcher, a static resource. None of them is
      // an API call and none of them is what a flood is made of.
      return true;
    }

    Instant now = Instant.now();
    List<RequestCounters.Decision> decisions = new ArrayList<>(4);

    String address = address(request);
    if (request.getRequestURI().startsWith(AUTH_PREFIX)) {
      // The stricter bucket, and keyed by address rather than by account: before
      // a login succeeds there is no account, and an attacker spraying one
      // password across many accounts would otherwise meet no counter at all.
      decisions.add(counters.count("auth", address, limits.authPerMinute(), now));
    }
    decisions.add(counters.count("ip", address, limits.perAddressPerMinute(), now));

    Optional<CallerContext.Caller> caller = CallerContext.current();
    caller.ifPresent(
        who -> {
          decisions.add(
              counters.count("user", who.userId().toString(), limits.perUserPerMinute(), now));
          // A caller acting for NO tenant is ordinary rather than exceptional:
          // managing one's own passkeys, listing one's tenants, opening the
          // change stream before choosing one. There is no tenant to charge, and
          // the per-user and per-address limits already bound them.
          if (who.tenantId() != null) {
            decisions.add(
                counters.count(
                    "tenant", who.tenantId().toString(), limits.perTenantPerMinute(), now));
          }
        });

    // Every scope is counted even when one of them is already over: the numbers
    // a client reads have to mean what they say on the next request too, and a
    // scope that stopped counting while another was refusing would drift.
    RequestCounters.Decision tightest =
        decisions.stream().min(Comparator.comparingLong(RequestCounters.Decision::remaining))
            .orElseThrow();
    report(response, tightest);

    RequestCounters.Decision exceeded =
        decisions.stream().filter(decision -> !decision.allowed()).findFirst().orElse(null);
    if (exceeded != null) {
      throw new RateLimitedException(
          exceeded.scope(), Duration.ofSeconds(exceeded.resetsInSeconds()));
    }
    return true;
  }

  /**
   * Writes the two headers that say where the caller stands.
   *
   * @param response the response being built
   * @param decision the tightest applicable scope
   */
  private static void report(HttpServletResponse response, RequestCounters.Decision decision) {
    response.setHeader(
        "RateLimit",
        "\"" + decision.scope() + "\";r=" + decision.remaining() + ";t=" + decision.resetsInSeconds());
    response.setHeader(
        "RateLimit-Policy",
        "\"" + decision.scope() + "\";q=" + decision.limit() + ";w=" + RequestCounters.WINDOW.toSeconds());
  }

  /**
   * The caller's address, as the forwarded-header filter left it.
   *
   * <p>{@code getRemoteAddr} and not the header: {@code TrustedForwardedHeaderFilter} has already
   * resolved the chain against the trusted hops, so this is the client's address and not the
   * proxy's — which is the difference between limiting one caller and limiting everybody behind
   * {@code web} as one (REQ-SEC-103).
   *
   * @param request the request
   * @return the address, or {@code "unknown"} when the container has none
   */
  private static String address(HttpServletRequest request) {
    String remote = request.getRemoteAddr();
    return remote == null || remote.isBlank() ? "unknown" : remote;
  }
}
