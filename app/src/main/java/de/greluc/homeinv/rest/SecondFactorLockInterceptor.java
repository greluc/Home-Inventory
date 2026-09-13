/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import de.greluc.homeinv.authorization.api.RoleRef;
import de.greluc.homeinv.authorization.api.SecondFactorPolicy;
import de.greluc.homeinv.identity.api.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Stops a role that needs a second factor from being used without one (REQ-AUTH-003).
 *
 * <h2>The role is granted; using it is what waits</h2>
 *
 * <p>An account with no authenticator still becomes an owner — creating a tenant makes one, and the
 * first owner of an instance is made by a one-shot with nobody to ask for a code. Refusing the
 * grant would mean either a tenant nobody owns or an exception for exactly the role that may do the
 * most. So the membership stands, and every request in that tenant is refused until the factor
 * exists: one rule that covers the bootstrap, the first tenant and every membership that predates
 * this.
 *
 * <h2>What stays reachable</h2>
 *
 * <p>The way out, and nothing else:
 *
 * <ul>
 *   <li>{@code /api/v1/auth/**} — signing in and out, and enrolling the factor this is asking for;
 *   <li>{@code /api/v1/me/**} — the list of tenants and the switch, so somebody locked out of one
 *       tenant can work in another where they hold an ordinary role.
 * </ul>
 *
 * <p>Deliberately <b>not</b> {@code /api/v1/tenants/**}: an owner who has not enrolled may not
 * administer members, change quotas or ask for an erasure. That is the point of the requirement.
 */
@Component
@RequiredArgsConstructor
public class SecondFactorLockInterceptor implements HandlerInterceptor {

  /** Where this application's own endpoints live. */
  private static final String ACCESS_LAYER = "de.greluc.homeinv.rest";

  private final SecondFactorPolicy policy;

  @Override
  public boolean preHandle(
      @NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull Object handler) {

    if (!(handler instanceof HandlerMethod method)
        || !method.getBeanType().getPackageName().startsWith(ACCESS_LAYER)
        || isTheWayOut(request.getRequestURI())) {
      return true;
    }

    AuthenticatedUser user = principal();
    if (user == null || user.role() == null || user.machine()) {
      // A service account holds a role and cannot hold a second factor
      // (REQ-AUTH-010). Asking it for one would be a token that never works; what
      // stands in for the factor is that the token expires and is revocable, and
      // that everything REQ-AUTH-011 guards is refused to it anyway.
      return true;
    }
    policy.requireEnrolled(new RoleRef(user.role(), user.roleDefinitionId()), user.userId());
    return true;
  }

  /**
   * Whether a path answers even for somebody who has not enrolled.
   *
   * @param path the request path
   * @return {@code true} when the request passes regardless
   */
  private static boolean isTheWayOut(String path) {
    return path.startsWith("/api/v1/auth/") || path.startsWith("/api/v1/me/");
  }

  /**
   * The caller, or null when there is no session.
   *
   * @return the principal
   */
  private static AuthenticatedUser principal() {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    return authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user
        ? user
        : null;
  }
}
