/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.tenancy.api.TenantInaccessibleException;
import de.greluc.homeinv.tenancy.api.TenantLifecycle;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Stops a tenant that is suspended or waiting to be erased from answering (REQ-TEN-011, O26).
 *
 * <h2>Read per request, and that is the point</h2>
 *
 * <p>05 §5.9 says access is "blocked immediately" when an erasure is asked for. A state carried in
 * the session would block nobody until their next sign-in, which for the session that just asked
 * for the erasure is exactly the wrong one. It is one indexed read of one row, on the tenant's own
 * primary key, under its own policy.
 *
 * <h2>What stays reachable</h2>
 *
 * <p>Three things, and each is there so that a blocked tenant is not also a dead end:
 *
 * <ul>
 *   <li>{@code /api/v1/auth/**}, so somebody can still sign in and out;
 *   <li>{@code /api/v1/me/**}, so they can see their tenants and switch to one that answers;
 *   <li>{@code /api/v1/tenants/*}, so the owner can read the state and withdraw the request —
 *       a blocked tenant whose owner could not undo the block would need the operator for
 *       something they are entitled to do themselves.
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class TenantAccessInterceptor implements HandlerInterceptor {

  /** Where this application's own endpoints live. */
  private static final String ACCESS_LAYER = "de.greluc.homeinv.rest";

  private final TenantLifecycle lifecycle;

  @Override
  public boolean preHandle(
      @NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull Object handler) {

    if (!(handler instanceof HandlerMethod method)
        || !method.getBeanType().getPackageName().startsWith(ACCESS_LAYER)) {
      return true;
    }
    if (TenantContext.current().isEmpty() || isAlwaysReachable(request.getRequestURI())) {
      return true;
    }
    if (lifecycle.state() != TenantLifecycle.State.ACTIVE) {
      throw new TenantInaccessibleException();
    }
    return true;
  }

  /**
   * Whether a path answers even for a tenant that is otherwise blocked.
   *
   * @param path the request path
   * @return {@code true} when the request passes regardless of the tenant's state
   */
  private static boolean isAlwaysReachable(String path) {
    return path.startsWith("/api/v1/auth/")
        || path.startsWith("/api/v1/me/")
        || path.startsWith("/api/v1/tenants/");
  }
}
