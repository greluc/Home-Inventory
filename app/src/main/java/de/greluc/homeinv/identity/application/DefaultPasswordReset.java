/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.identity.application;

import de.greluc.homeinv.identity.api.PasswordPolicy;
import de.greluc.homeinv.identity.api.PasswordReset;
import de.greluc.homeinv.identity.api.TooManyAttemptsException;
import de.greluc.homeinv.identity.api.UserSessions;
import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.identity.infrastructure.PasswordResetQueries;
import de.greluc.homeinv.notification.api.SecurityNotifications;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Getting back into an account whose password is gone (REQ-SEC-018).
 *
 * <h2>The order of the last three steps, and why it is that order</h2>
 *
 * <p>Set the password, end every session, then tell the old address. The notification is last
 * because it is the only step that can fail on somebody else's mail server, and a reset that rolled
 * back because a message could not be queued would leave the person locked out with no way to try
 * again. Queuing is cheap and in the same transaction; <em>delivering</em> happens in the worker
 * afterwards (ADR-0066).
 *
 * <h2>Asking tells the asker nothing</h2>
 *
 * <p>{@link #request} does the same amount of work for an address with an account and one without:
 * the same hashing, the same look-up, and nothing in the answer that distinguishes them. A reset
 * endpoint that said "no such account" would be a way to ask the instance who is registered on it
 * (REQ-SEC-025, REQ-SEC-110).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultPasswordReset implements PasswordReset {

  /** 256 bits, base64url without padding, so the token survives a URL unescaped. */
  private static final int TOKEN_BYTES = 32;

  private static final SecureRandom RANDOM = new SecureRandom();

  /**
   * How long a link works.
   *
   * <p>Thirty minutes: long enough to find the message, short enough that an old mailbox is not a
   * standing key to the account (REQ-SEC-018).
   */
  private static final Duration VALIDITY = Duration.ofMinutes(30);

  /** What the message with the link says. {@code {from}}, {@code {baseUrl}} and {@code {token}}. */
  private static final String RESET_BODY =
      """
      Somebody asked to reset the password of your Home Inventory account{from}.

      Open this link within 30 minutes to choose a new one:

      {baseUrl}/reset-password?token={token}

      The link works once. If this was not you, you can ignore this message —
      your password has not changed, and nobody can change it without this link.
      """;

  /** What the message to the old address says. {@code {sessions}}. */
  private static final String CHANGED_BODY =
      """
      The password of your Home Inventory account was just changed, and {sessions}.

      If this was you, there is nothing to do.

      If it was not, somebody else has had access to this mailbox or to your
      account. Ask for a password reset now — this message went to the address
      the account had before the change, which is why you are reading it.
      """;

  private final PasswordResetQueries resets;
  private final AppUserRepository users;
  private final PasswordEncoder passwordEncoder;
  private final UserSessions sessions;
  private final SecurityNotifications notifications;
  private final PasswordPolicy policy;
  private final LoginRateLimiter throttle;
  private final Clock clock;

  /** Where this instance is reached, so the link in the message leads back to it. */
  @Value("${homeinv.public-base-url}")
  private String publicBaseUrl;

  @Override
  @Transactional
  public void request(String email, String ip) {
    Instant now = clock.instant();

    // The throttle is part of what a reset IS, not of how HTTP reaches it: what
    // it protects is somebody else's mailbox, and a second caller of this port
    // would otherwise arrive without it. Its own counters, so a run of requests
    // cannot lock the account holder out of signing in (REQ-SEC-018).
    java.time.Duration wait = throttle.retryAfterReset(email, ip);
    if (!wait.isZero()) {
      throw new TooManyAttemptsException(wait);
    }
    throttle.recordResetRequest(email, ip);
    // Minted before the account is looked up, and always. The work is then the
    // same either way, which is what makes the two cases indistinguishable from
    // outside — including in how long they take.
    String token = SingleUseTokens.mint();
    String tokenHash = SingleUseTokens.hash(token);

    Optional<AppUser> account = users.findByEmail(email);
    if (account.isEmpty() || !account.get().canAuthenticate()) {
      // Nothing happens, and the caller cannot tell which of the two it was. A
      // locked account is deliberately on this side of the line: a reset that
      // worked for one would say that it is locked.
      log.info("A password reset was asked for an address with no usable account.");
      return;
    }

    AppUser user = account.get();
    resets.open(user.getId(), tokenHash, now.plus(VALIDITY), ip);

    notifications.raise(
        new SecurityNotifications.NewSecurityNotification(
            user.getId(),
            "security.password-reset",
            user.getEmail(),
            "Reset your Home Inventory password",
            resetMessage(token, ip),
            null,
            user.getLocale(),
            // One key per reset rather than per account: asking twice replaces
            // the token, and the second message has to go out or the first
            // token — now dead — would be the only one anybody received.
            "password-reset:" + tokenHash));
  }

  @Override
  @Transactional
  public void complete(String token, String newPassword) {
    // Before the token is looked at, so that a password the policy refuses does
    // not spend the link: the person would otherwise have to ask for a new one
    // because they typed a short password once (REQ-SEC-011).
    policy.check(newPassword);

    Instant now = clock.instant();
    PasswordResetQueries.OpenReset reset =
        resets
            .find(SingleUseTokens.hash(token), now)
            .orElseThrow(InvalidResetTokenException::new);

    // Spent first. Two redemptions racing each other both find the row open, and
    // only the one that changes it may go on to set a password.
    if (!resets.spend(reset.id())) {
      throw new InvalidResetTokenException();
    }

    AppUser user = users.findById(reset.userId()).orElseThrow(InvalidResetTokenException::new);
    user.replacePasswordHash(passwordEncoder.encode(newPassword), now);
    users.save(user);

    // Somebody who took the account over is signed out by the real owner's
    // reset. Without this the reset would return the password and leave the
    // intruder logged in (REQ-SEC-018).
    int ended = sessions.endAll(user.getId());

    notifications.raise(
        new SecurityNotifications.NewSecurityNotification(
            user.getId(),
            "security.password-changed",
            user.getEmail(),
            "Your Home Inventory password was changed",
            changedMessage(ended),
            null,
            user.getLocale(),
            "password-changed:" + reset.id()));

    log.info("Account {} completed a password reset; {} session(s) were ended.", user.getId(), ended);
  }

  /**
   * The message that carries the link.
   *
   * @param token the token, which appears here and nowhere else
   * @param ip where the request came from
   * @return the plain-text body
   */
  private String resetMessage(String token, String ip) {
    // Placeholders rather than a format string. The newlines here are the shape
    // of the message somebody reads, not a platform's line separator, and
    // `formatted` would have them judged as one — plus a percent sign in a
    // future message would become a format specifier nobody meant.
    return RESET_BODY
        .replace("{from}", ip == null || ip.isBlank() ? "" : " from " + ip)
        .replace("{baseUrl}", publicBaseUrl)
        .replace("{token}", token);
  }

  /**
   * The message that goes to the address the account had.
   *
   * @param endedSessions how many sessions were ended
   * @return the plain-text body
   */
  private String changedMessage(int endedSessions) {
    String sessions =
        endedSessions == 0
            ? "no session was open at the time"
            : endedSessions == 1
                ? "the one session that was open has been ended"
                : "all " + endedSessions + " sessions that were open have been ended";
    return CHANGED_BODY.replace("{sessions}", sessions);
  }

}
