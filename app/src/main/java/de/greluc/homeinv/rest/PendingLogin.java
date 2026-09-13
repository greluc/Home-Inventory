/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import de.greluc.homeinv.identity.api.AuthenticatedUser;
import de.greluc.homeinv.identity.api.InvalidCredentialsException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.io.Serializable;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * A login that has passed the password and is waiting for the second factor (REQ-AUTH-002).
 *
 * <h2>Why it is held server-side</h2>
 *
 * <p>The obvious alternative is a short-lived token handed to the client between the two steps.
 * That token is a bearer credential for half a login, and it would travel, be logged and be
 * replayed. This keeps the half-done login in the session — which is already server-side in Valkey
 * (12 §12.4) and already shared across instances — and the client holds nothing but its cookie.
 *
 * <h2>Why the session id is rotated twice</h2>
 *
 * <p>Once when the password is accepted and again when the code is: an id planted by an attacker
 * must not survive into the pending state, and a pending id somebody observed must not survive into
 * the authenticated one. Session fixation is cheap to defend against at each step and expensive to
 * discover later.
 *
 * <p>The window is five minutes. Long enough to fetch a phone, short enough that a session left
 * open on a shared machine is not a login waiting to be finished by whoever sits down next.
 */
@Component
@RequiredArgsConstructor
public class PendingLogin {

  /** Where the half-done login sits in the session. */
  private static final String ATTRIBUTE = "homeinv.pending-login";

  /** How long a code may be answered in. */
  private static final Duration WINDOW = Duration.ofMinutes(5);

  private final Clock clock;

  /**
   * A login waiting for its second factor.
   *
   * @param user who has proved their password
   * @param since when they proved it
   */
  record Pending(AuthenticatedUser user, Instant since) implements Serializable {

    /** The session is serialised into Valkey; a changed shape must not deserialise wrong. */
    private static final long serialVersionUID = 1L;
  }

  /**
   * Remembers a login that still owes a code.
   *
   * <p>Rotates the session id first, so that whatever id the client arrived with is not the id the
   * pending login is attached to.
   *
   * @param request the servlet request, for the session
   * @param user who has proved their password
   */
  void remember(HttpServletRequest request, AuthenticatedUser user) {
    if (request.getSession(false) != null) {
      request.changeSessionId();
    } else {
      request.getSession(true);
    }
    request.getSession().setAttribute(ATTRIBUTE, new Pending(user, Instant.now(clock)));
  }

  /**
   * Reads the pending login without spending it.
   *
   * <p>For the one call that has to know <em>who</em> is half-way through a login without
   * finishing it: the passkey challenge, which needs the account to say which credentials may
   * answer. Spending the pending login there would mean a client could never actually complete one.
   *
   * @param request the servlet request, for the session
   * @return who is half-way through logging in
   * @throws InvalidCredentialsException when there is no pending login, or it has expired
   */
  AuthenticatedUser peek(HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    Object stored = session == null ? null : session.getAttribute(ATTRIBUTE);
    if (!(stored instanceof Pending pending)
        || pending.since().plus(WINDOW).isBefore(Instant.now(clock))) {
      throw new InvalidCredentialsException();
    }
    return pending.user();
  }

  /**
   * Takes the pending login, if there is a live one.
   *
   * <p>Removed as it is read, whether or not the code that follows turns out to be right: a pending
   * login is answered once. A wrong code therefore costs the password again, which is the point —
   * an attacker with a stolen session cookie gets one guess per password, not an unlimited supply.
   *
   * @param request the servlet request, for the session
   * @return who was half-way through logging in
   * @throws InvalidCredentialsException when there is no pending login, or it has expired. The same
   *     exception a wrong password raises: from outside, "your window ran out" and "you never had
   *     one" are the same thing, and the client's answer to both is the login form
   */
  AuthenticatedUser claim(HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    if (session == null) {
      throw new InvalidCredentialsException();
    }
    Object stored = session.getAttribute(ATTRIBUTE);
    session.removeAttribute(ATTRIBUTE);
    if (!(stored instanceof Pending pending)
        || pending.since().plus(WINDOW).isBefore(Instant.now(clock))) {
      throw new InvalidCredentialsException();
    }
    return pending.user();
  }
}
