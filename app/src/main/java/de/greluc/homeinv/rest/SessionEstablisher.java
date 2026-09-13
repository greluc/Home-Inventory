/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import de.greluc.homeinv.identity.api.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

/**
 * Turns an authenticated principal into a web session.
 *
 * <p>One place, because there are two ways in — a password alone, and a password followed by a code
 * — and the second was written by copying the first. The part worth not copying is the session
 * fixation defence: the id is rotated before the security context is written, so an id an attacker
 * planted beforehand is never the id the authenticated session ends up with.
 *
 * <p>It also records <b>when the second factor was last proved</b>. Nothing reads that yet; the
 * re-confirmation of {@code REQ-AUTH-011} does, and recording it at the one point where a factor is
 * actually proved is what keeps that check from being bolted on somewhere it can be forgotten.
 */
@Component
@RequiredArgsConstructor
public class SessionEstablisher {

  /**
   * When the second factor was last proved in this session, as an epoch-second {@code Long}.
   *
   * <p>Public because {@code SecondFactorStepUpIT} moves it: the window of REQ-AUTH-011 is fifteen
   * minutes and a test that waited them out would add a quarter of an hour to the build. A second
   * copy of the name in the test would be the thing that drifts.
   */
  public static final String SECOND_FACTOR_AT = "homeinv.second-factor-at";

  private final Clock clock;

  private final SecurityContextRepository securityContextRepository =
      new HttpSessionSecurityContextRepository();

  /**
   * When the second factor was last proved in this session.
   *
   * <p>Static, and reading the session directly, because two unrelated places need it: the filter
   * that publishes the caller context, and the interceptor that guards the operations of
   * REQ-AUTH-011. A second copy of the attribute name is how the two would come to disagree.
   *
   * @param request the servlet request
   * @return the instant, or null when no factor has been proved in this session
   */
  static Instant secondFactorProvedAt(jakarta.servlet.http.HttpServletRequest request) {
    jakarta.servlet.http.HttpSession session = request.getSession(false);
    Object stored = session == null ? null : session.getAttribute(SECOND_FACTOR_AT);
    return stored instanceof Long epochSecond ? Instant.ofEpochSecond(epochSecond) : null;
  }

  /**
   * Records that the second factor has just been proved again (REQ-AUTH-011).
   *
   * @param request the servlet request, whose session carries it
   */
  void secondFactorProved(jakarta.servlet.http.HttpServletRequest request) {
    request.getSession().setAttribute(SECOND_FACTOR_AT, Instant.now(clock).getEpochSecond());
  }

  /**
   * Establishes the session and returns what the client is told about it.
   *
   * @param user the authenticated principal
   * @param request the servlet request, for the session
   * @param response the servlet response, which the security context is written to
   * @param secondFactorProved whether a second factor was verified as part of this login
   * @return the session view
   */
  AuthController.SessionView establish(
      AuthenticatedUser user,
      HttpServletRequest request,
      HttpServletResponse response,
      boolean secondFactorProved) {

    // Rotate the id rather than discarding the session. This is the servlet
    // container's own fixation defence: the id an attacker may have planted is
    // replaced, while the session object itself survives — which
    // invalidate-and-recreate does not.
    if (request.getSession(false) != null) {
      request.changeSessionId();
    } else {
      request.getSession(true);
    }

    if (secondFactorProved) {
      request.getSession().setAttribute(SECOND_FACTOR_AT, Instant.now(clock).getEpochSecond());
    }

    Authentication token = UsernamePasswordAuthenticationToken.authenticated(user, null, List.of());
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(token);
    SecurityContextHolder.setContext(context);
    securityContextRepository.saveContext(context, request, response);

    return new AuthController.SessionView(
        user.userId(), user.tenantId(), user.email(), user.locale(), user.role());
  }
}
