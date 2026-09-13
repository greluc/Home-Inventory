/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import de.greluc.homeinv.identity.api.AuthenticatedUser;
import de.greluc.homeinv.identity.api.ServiceAccounts;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates a machine by the token it presents (REQ-AUTH-010).
 *
 * <h2>Why a filter and not a login</h2>
 *
 * <p>A service account has no session: it presents its token on every request, which is what makes
 * it revocable at once and what keeps a machine from holding a cookie it never renews. So there is
 * nothing to establish and nothing to remember — this reads the header, resolves the token, and
 * publishes a principal for the duration of the request.
 *
 * <h2>What it does not do</h2>
 *
 * <p>It writes no session and no security context repository entry, so a token cannot become a
 * session by being presented once. It also does nothing at all when a session is already
 * established: a browser that happens to carry a stray {@code Authorization} header must not be
 * able to swap identities mid-request.
 *
 * <p>A token that is unknown, revoked or expired is not an error here — the filter simply publishes
 * nobody, and the request goes on to be refused by the ordinary rules with {@code unauthenticated}.
 * Answering differently would say which tokens once existed.
 */
@Component
@RequiredArgsConstructor
public class ServiceAccountAuthenticationFilter extends OncePerRequestFilter {

  /** The scheme the token travels under. */
  private static final String SCHEME = "Bearer ";

  private final ServiceAccounts serviceAccounts;

  @Override
  protected void doFilterInternal(
      @NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull FilterChain chain)
      throws ServletException, IOException {

    String header = request.getHeader("Authorization");
    // Read once: two calls could answer differently, and the second would be the
    // one dereferenced.
    Authentication established = SecurityContextHolder.getContext().getAuthentication();
    boolean alreadySomebody =
        established != null
            && established.isAuthenticated()
            && established.getPrincipal() instanceof AuthenticatedUser;

    if (header == null || !header.startsWith(SCHEME) || alreadySomebody) {
      chain.doFilter(request, response);
      return;
    }

    serviceAccounts
        .authenticate(header.substring(SCHEME.length()).trim())
        .ifPresent(
            machine -> {
              SecurityContext context = SecurityContextHolder.createEmptyContext();
              context.setAuthentication(
                  UsernamePasswordAuthenticationToken.authenticated(machine, null, List.of()));
              SecurityContextHolder.setContext(context);
            });

    try {
      chain.doFilter(request, response);
    } finally {
      // Cleared here rather than left to the container: the thread is pooled, and
      // a principal left behind is the next request's caller.
      SecurityContextHolder.clearContext();
    }
  }
}
