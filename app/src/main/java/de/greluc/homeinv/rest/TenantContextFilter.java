/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import de.greluc.homeinv.identity.api.AuthenticatedUser;
import de.greluc.homeinv.platform.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Publishes the authenticated session's tenant to {@link TenantContext} for the duration of a
 * request.
 *
 * <p>This is the only writer of that context in the request path, and it takes the value from the
 * security context - never from a header, a path segment or a parameter (REQ-SEC-004). A tenant the
 * caller could choose would make the row-level security policies agree with the application's
 * mistake instead of catching it.
 *
 * <p>It lives in the access layer rather than in {@code platform}, and that was not a preference:
 * reading {@code AuthenticatedUser} makes this class depend on {@code identity}, and the shared
 * kernel must depend on no block at all. In {@code platform} it closed a cycle
 * identity -> tenancy -> platform -> identity, which the modularity test found on its first run.
 *
 * <p>The clearing in the {@code finally} block is the point of the class as much as the setting is.
 * Threads are pooled; one handed back with a tenant still attached would serve the next request as
 * the wrong tenant, and that request might be one that never establishes a context of its own.
 */
@Component
public class TenantContextFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {

    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    Object principal = authentication == null ? null : authentication.getPrincipal();

    if (principal instanceof AuthenticatedUser user) {
      try {
        MDC.put("tenantId", user.tenantId().toString());
        MDC.put("actorId", user.userId().toString());
        TenantContext.runAs(user.tenantId(), () -> proceed(request, response, chain));
      } finally {
        TenantContext.clear();
        MDC.remove("tenantId");
        MDC.remove("actorId");
      }
    } else {
      chain.doFilter(request, response);
    }
  }

  /**
   * Runs the rest of the chain, turning the checked exceptions into unchecked ones so the body fits
   * a {@link Runnable}.
   *
   * <p>The wrapping is unwrapped again by the servlet container's own handling; nothing is
   * swallowed, and the original exception stays the cause.
   *
   * @param request the request
   * @param response the response
   * @param chain the remaining chain
   */
  private static void proceed(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain) {
    try {
      chain.doFilter(request, response);
    } catch (IOException | ServletException e) {
      throw new FilterExecutionException(e);
    }
  }

  /** Carries a checked filter failure out of the {@link Runnable} the tenant scope needs. */
  static final class FilterExecutionException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    FilterExecutionException(Throwable cause) {
      super(cause);
    }
  }
}
