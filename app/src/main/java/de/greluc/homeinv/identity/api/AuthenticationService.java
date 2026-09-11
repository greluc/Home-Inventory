/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.identity.api;

/**
 * Verifying a login.
 *
 * <p>Published, because the access layer turns its outcome into a session. The implementation and
 * everything it depends on - the password encoder, the throttle, the user table - stay inside the
 * block.
 */
public interface AuthenticationService {

  /**
   * Verifies credentials and returns the principal for the session.
   *
   * <p>Every failure a caller may learn about is the same failure: unknown address, wrong password,
   * locked account (REQ-SEC-016). The real reason is logged, where an operator can use it and a
   * caller cannot.
   *
   * @param email the address entered
   * @param password the password entered
   * @param clientIp the caller's address, for the per-IP throttle
   * @return the authenticated principal, carrying the user and the tenant
   * @throws TooManyAttemptsException when the throttle requires a wait, carrying how long
   * @throws InvalidCredentialsException for every other failure, indistinguishably
   */
  AuthenticatedUser login(String email, String password, String clientIp);
}
