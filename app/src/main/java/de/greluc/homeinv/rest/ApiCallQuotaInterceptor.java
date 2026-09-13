/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.tenancy.api.QuotaGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Counts a tenant's API calls against its monthly allowance (REQ-TEN-009).
 *
 * <h2>Not the rate limiter</h2>
 *
 * <p>REQ-SEC-064's rate limiting answers {@code 429} with a {@code Retry-After}: too fast, come
 * back in a moment. This answers {@code 403} with {@code quota-exceeded}: the allowance for this
 * month is spent, and waiting a moment changes nothing. Two different things to tell somebody, and
 * only one of them is worth waiting out.
 *
 * <h2>What is not counted</h2>
 *
 * <p>Four exemptions, and each is there so that a tenant which has spent its allowance is not also
 * locked out of finding that out:
 *
 * <ul>
 *   <li>requests with no tenant context — there is nobody to charge;
 *   <li>{@code /api/v1/auth/**}, so somebody can still sign in and out;
 *   <li>{@code /api/v1/me/**}, so they can still see their tenants and switch to another one;
 *   <li>the quota endpoint itself, so "how much have I used" is answerable when the answer is
 *       "all of it".
 * </ul>
 *
 * <p>Everything else counts, reads included. A quota on API calls that only counted writes would be
 * a quota on writes.
 */
@Component
@RequiredArgsConstructor
public class ApiCallQuotaInterceptor implements HandlerInterceptor {

  /** Where this application's own endpoints live. */
  private static final String ACCESS_LAYER = "de.greluc.homeinv.rest";

  private final QuotaGuard quotas;

  @Override
  public boolean preHandle(
      @NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull Object handler) {

    if (!(handler instanceof HandlerMethod method)
        || !method.getBeanType().getPackageName().startsWith(ACCESS_LAYER)) {
      // The actuator, the document resource, the error dispatcher. None of them
      // is a tenant's use of the API.
      return true;
    }

    if (TenantContext.current().isEmpty() || isExempt(request.getRequestURI())) {
      return true;
    }

    quotas.require(QuotaGuard.Quota.API_CALLS, 1);
    return true;
  }

  /**
   * Whether a path is one a tenant may reach with its allowance spent.
   *
   * @param path the request path
   * @return {@code true} when the call is not counted
   */
  private static boolean isExempt(String path) {
    return path.startsWith("/api/v1/auth/")
        || path.startsWith("/api/v1/me/")
        || path.endsWith("/quotas");
  }
}
