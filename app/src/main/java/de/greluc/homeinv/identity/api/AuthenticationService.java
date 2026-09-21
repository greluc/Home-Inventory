/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.identity.api;

import java.util.UUID;

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
   * locked account (REQ-SEC-110). The real reason is logged, where an operator can use it and a
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

  /**
   * The principal a VERIFIED foreign identity signs in as (REQ-AUTH-005).
   *
   * <p>No password and no throttle: there is no secret to guess here. The provider authenticated
   * the person and the core verified the token that says so, which is the step a password
   * replaces. Everything after it is shared with {@link #login} — the account has to be one
   * that may authenticate at all, and the membership is resolved the same way, so a federated
   * session is the same session through a different door.
   *
   * <p>It establishes nothing, and it does not answer the second factor. That is asked for
   * where a password login asks for it: a provider proved who somebody is, not that they hold
   * the authenticator this instance knows about.
   *
   * @param userId the account the verified identity is linked to
   * @param clientIp the caller's address, for the log
   * @return the authenticated principal, carrying the user and the tenant
   * @throws InvalidCredentialsException when the account may not authenticate — locked,
   *     disabled or gone. The same exception a password login raises, because what the
   *     caller is told has to be the same
   */
  AuthenticatedUser signInFederated(UUID userId, String clientIp);
}
