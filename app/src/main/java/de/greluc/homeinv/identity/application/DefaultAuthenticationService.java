/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.identity.application;

import de.greluc.homeinv.identity.api.AuthenticatedUser;
import de.greluc.homeinv.identity.api.AuthenticationService;
import de.greluc.homeinv.identity.api.InvalidCredentialsException;
import de.greluc.homeinv.identity.api.TooManyAttemptsException;
import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.tenancy.api.MembershipLookup;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies a login and produces the session principal.
 *
 * <h2>Every failure looks the same</h2>
 *
 * <p>Unknown address, wrong password, locked account: one {@link InvalidCredentialsException}, one
 * message, and the password is verified even when no user was found. Returning early on an unknown
 * address would make that case measurably faster than a wrong password, and the difference is an
 * oracle for whether an address has an account here — which {@code REQ-SEC-016} exists to close.
 * The dummy hash below is what keeps the work comparable.
 *
 * <h2>The tenant comes from the membership</h2>
 *
 * <p>Not from the request, not from a header, not from a subdomain. Stage 0 has one tenant per
 * user; stage 1 lets a user switch between several without re-authenticating (REQ-TEN-003), which
 * changes which membership is chosen here and nothing else in the system.
 */
@Service
@Slf4j
public class DefaultAuthenticationService implements AuthenticationService {

  /**
   * An Argon2id hash of a value nobody knows, used to spend the same work on an unknown address as
   * on a known one.
   *
   * <p>Computed once at startup rather than stored as a constant: a hard-coded PHC string would
   * carry whatever parameters were current the day it was written, and the timing it is meant to
   * match would drift away from it the moment the cost is raised.
   */
  private final String dummyHash;

  private final AppUserRepository users;
  private final MembershipLookup memberships;
  private final PasswordEncoder passwordEncoder;
  private final LoginRateLimiter rateLimiter;
  private final Clock clock;

  /**
   * Creates the service and pre-computes the timing-equalising hash.
   *
   * @param users the user repository
   * @param memberships the tenant membership lookup
   * @param passwordEncoder the Argon2id encoder
   * @param rateLimiter the login throttle
   * @param clock the clock use cases read time from
   */
  public DefaultAuthenticationService(
      AppUserRepository users,
      MembershipLookup memberships,
      PasswordEncoder passwordEncoder,
      LoginRateLimiter rateLimiter,
      Clock clock) {
    this.users = users;
    this.memberships = memberships;
    this.passwordEncoder = passwordEncoder;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
    this.dummyHash = passwordEncoder.encode("this value is never a password");
  }

  /**
   * Verifies credentials and returns the principal for the session.
   *
   * @param email the address entered
   * @param password the password entered
   * @param clientIp the caller's address, for the per-IP throttle
   * @return the authenticated principal, carrying the user and the tenant
   * @throws TooManyAttemptsException when the throttle requires a wait, carrying how long
   * @throws InvalidCredentialsException for every other failure, indistinguishably
   */
  @Override
  @Transactional
  public AuthenticatedUser login(String email, String password, String clientIp) {
    Duration wait = rateLimiter.retryAfter(email, clientIp);
    if (!wait.isZero()) {
      throw new TooManyAttemptsException(wait);
    }

    Optional<AppUser> found = users.findByEmail(email);

    // Verified in every branch. An early return on an unknown address would make
    // that case measurably faster, and the difference says whether the address
    // has an account here.
    String hash = found.map(AppUser::passwordHash).orElse(dummyHash);
    boolean passwordMatches = passwordEncoder.matches(password, hash);

    if (found.isEmpty() || !passwordMatches || !found.get().canAuthenticate()) {
      rateLimiter.recordFailure(email, clientIp);
      // The reason is logged; it is never returned. An operator investigating a
      // lockout needs to tell "wrong password" from "locked account"; the caller
      // must not be able to.
      log.info(
          "Failed login for '{}' from {} ({})",
          email,
          clientIp,
          found.isEmpty() ? "no such account" : !passwordMatches ? "wrong password" : "account locked");
      throw new InvalidCredentialsException();
    }

    AppUser user = found.get();
    UUID tenantId =
        memberships
            .primaryTenantOf(user.getId())
            .orElseThrow(
                () -> {
                  // A user with no membership cannot act, and saying so precisely
                  // would confirm the account exists. It is an invalid credential
                  // from outside and a data problem from inside.
                  log.warn("User {} authenticated but belongs to no tenant", user.getId());
                  return new InvalidCredentialsException();
                });

    rehashIfCostRaised(user, password);
    rateLimiter.recordSuccess(email, clientIp);

    log.info("User {} logged in for tenant {}", user.getId(), tenantId);
    return new AuthenticatedUser(user.getId(), tenantId, user.getEmail());
  }

  /**
   * Re-hashes the password when the stored hash was made with a lower cost than the current one.
   *
   * <p>This is the only moment at which a raised cost reaches an existing account: nobody can
   * re-hash without the plaintext, and the plaintext exists only during a successful login
   * (REQ-SEC-010, "raisable by configuration").
   *
   * @param user the user who just logged in
   * @param password the plaintext they presented, still in memory for this call only
   */
  private void rehashIfCostRaised(AppUser user, String password) {
    if (passwordEncoder.upgradeEncoding(user.passwordHash())) {
      user.replacePasswordHash(passwordEncoder.encode(password), Instant.now(clock));
      log.info("Re-hashed the password of user {} at the current cost", user.getId());
    }
  }
}
