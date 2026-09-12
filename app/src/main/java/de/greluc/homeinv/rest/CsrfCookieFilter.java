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
import org.springframework.lang.NonNull;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Makes sure the CSRF cookie is actually sent.
 *
 * <h2>The problem this solves, which is not obvious</h2>
 *
 * <p>Spring Security defers the CSRF token: {@code CsrfFilter} puts a <em>supplier</em> in the
 * request and only resolves it when something asks, and the cookie is written when it resolves. A
 * safe request asks nothing, so a client that has only ever made {@code GET}s has no token — and its
 * first {@code POST} is then refused with a {@code 403} nobody expected. The web client does exactly
 * that: it starts with {@code GET /auth/me}, searches with {@code GET}, and the first mutating call
 * a user makes is creating something.
 *
 * <p>Login is the exception that hides it in a test: it is on {@code ignoringRequestMatchers}, so a
 * successful sign-in also resolves nothing.
 *
 * <p>This filter resolves the token on every request, which is the mechanism Spring's own
 * documentation prescribes for a single-page application. Resolving it costs a lookup in the session;
 * being refused on the first write costs a user their work.
 *
 * <p>Not a {@code @Component}: a filter bean is registered with the servlet container as well, so it
 * would run twice — once for the container and once inside the security chain. It is constructed by
 * {@link WebSecurityConfiguration}, which is the only place it belongs.
 */
public class CsrfCookieFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(
      @NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull FilterChain chain)
      throws ServletException, IOException {

    Object token = request.getAttribute(CsrfToken.class.getName());
    if (token instanceof CsrfToken csrf) {
      // The call is the point, not the value: it is what forces the deferred
      // token to load and the repository to write the cookie.
      csrf.getToken();
    }
    chain.doFilter(request, response);
  }
}
