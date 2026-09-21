/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import de.greluc.homeinv.identity.api.AuthenticatedUser;
import de.greluc.homeinv.identity.api.UserSessions;
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
   * What the client calls itself, shortened and stripped.
   *
   * <p>A user agent and nothing derived from it. What it is for is telling "my phone" from "the
   * machine at work" in a list somebody is deciding from, and a parsed one would be a guess
   * presented as a fact. Control characters go because this string is shown to a person and stored
   * in a session (REQ-SEC-032), and the length is bounded because a header is caller-supplied.
   *
   * @param request the servlet request
   * @return the user agent, at most 200 characters, or {@code "unknown"}
   */
  private static String deviceOf(HttpServletRequest request) {
    String agent = request.getHeader("User-Agent");
    if (agent == null || agent.isBlank()) {
      return "unknown";
    }
    String cleaned = agent.replaceAll("\\p{Cntrl}", "").trim();
    return cleaned.length() > 200 ? cleaned.substring(0, 200) : cleaned;
  }

  /**
   * The network a session came from, with the host part removed.
   *
   * <p>{@code 203.0.113.0/24} rather than the address, and the first four groups of an IPv6
   * address rather than the address: enough to tell a familiar network from a strange one, which is
   * what somebody looking at the list is deciding, and not enough to be a location history.
   * REQ-PRIV-006 governs how long an address may be kept at all; the honest way to keep one in a
   * session for a month is not to keep the whole of it.
   *
   * @param request the servlet request
   * @return the truncated address
   */
  private static String originOf(HttpServletRequest request) {
    String address = request.getRemoteAddr();
    if (address == null || address.isBlank()) {
      return "unknown";
    }
    if (address.contains(":")) {
      String[] groups = address.split(":");
      return String.join(":", java.util.Arrays.copyOf(groups, Math.min(4, groups.length)))
          + "::/64";
    }
    String[] octets = address.split("\\.");
    return octets.length == 4
        ? octets[0] + "." + octets[1] + "." + octets[2] + ".0/24"
        : address;
  }

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
  SessionView establish(
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

    // What the session overview of REQ-AUTH-009 shows. Recorded here because this
    // is where a session begins, and because neither value is available later:
    // the list is read from the store, not from a request.
    request.getSession().setAttribute(UserSessions.DEVICE_ATTRIBUTE, deviceOf(request));
    request.getSession().setAttribute(UserSessions.ORIGIN_ATTRIBUTE, originOf(request));

    Authentication token = UsernamePasswordAuthenticationToken.authenticated(user, null, List.of());
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(token);
    SecurityContextHolder.setContext(context);
    securityContextRepository.saveContext(context, request, response);

    return new SessionView(
        user.userId(), user.tenantId(), user.email(), user.locale(), user.role());
  }
}
