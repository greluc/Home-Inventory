/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import de.greluc.homeinv.identity.api.InvalidSecondFactorException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * The challenge a passkey ceremony was started with, between the two calls (REQ-AUTH-002).
 *
 * <p>It lives in the session and is <b>consumed</b> by the response that answers it. Both halves
 * matter: a challenge the client sent back to itself would be no challenge at all — the whole point
 * is that the server chose it — and one that survived its answer would let a captured assertion be
 * replayed for as long as the session lasted.
 *
 * <p>Two slots, because the two ceremonies can be in flight at once: somebody registering a second
 * passkey is signed in, and a re-confirmation could be asked for in another tab. One slot would
 * make either ceremony cancel the other.
 */
final class PasskeyChallenge {

  /** Where the registration ceremony's challenge sits. */
  private static final String REGISTRATION = "homeinv.passkey-registration-challenge";

  /** Where the assertion ceremony's challenge sits. */
  private static final String ASSERTION = "homeinv.passkey-assertion-challenge";

  private PasskeyChallenge() {}

  /**
   * Remembers the challenge a registration was started with.
   *
   * @param request the servlet request
   * @param challenge the challenge
   */
  static void rememberRegistration(HttpServletRequest request, String challenge) {
    request.getSession().setAttribute(REGISTRATION, challenge);
  }

  /**
   * Remembers the challenge an assertion was started with.
   *
   * @param request the servlet request
   * @param challenge the challenge
   */
  static void rememberAssertion(HttpServletRequest request, String challenge) {
    request.getSession().setAttribute(ASSERTION, challenge);
  }

  /**
   * Takes the registration challenge, once.
   *
   * @param request the servlet request
   * @return the challenge
   * @throws InvalidSecondFactorException when no ceremony is in flight
   */
  static String claimRegistration(HttpServletRequest request) {
    return take(request, REGISTRATION);
  }

  /**
   * Takes the assertion challenge, once.
   *
   * @param request the servlet request
   * @return the challenge
   * @throws InvalidSecondFactorException when no ceremony is in flight
   */
  static String claim(HttpServletRequest request) {
    return take(request, ASSERTION);
  }

  /**
   * Reads an attribute and removes it in the same breath.
   *
   * @param request the servlet request
   * @param attribute which challenge
   * @return the challenge
   */
  private static String take(HttpServletRequest request, String attribute) {
    HttpSession session = request.getSession(false);
    Object stored = session == null ? null : session.getAttribute(attribute);
    if (session != null) {
      session.removeAttribute(attribute);
    }
    if (!(stored instanceof String challenge)) {
      throw new InvalidSecondFactorException();
    }
    return challenge;
  }
}
