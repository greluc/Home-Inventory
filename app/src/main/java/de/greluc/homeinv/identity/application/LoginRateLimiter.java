/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.identity.application;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Slows down repeated failed logins, per account and per IP address
 * ({@code REQ-SEC-012}).
 *
 * <h2>Why both keys, and why that is not paranoia</h2>
 *
 * <p>Counting per account alone lets an attacker spray one password across thousands of accounts
 * without ever tripping a counter. Counting per IP alone lets them rotate addresses and hammer one
 * account. Neither key is sufficient and the requirement names both; whichever is further along
 * decides the delay.
 *
 * <p>Counting per account also hands an attacker a way to lock a victim out by failing on purpose.
 * That is why this class delays rather than locks: the delay expires on its own, and the honest
 * user who comes back a minute later gets in. A hard lockout would turn a rate limiter into a
 * denial-of-service tool aimed at our own users.
 *
 * <h2>Why Valkey and not memory</h2>
 *
 * <p>The {@code api} role scales horizontally. A counter in the heap would be per instance, so four
 * instances would mean four times the allowance — and the attacker gets to pick which one answers.
 *
 * <h2>The delay</h2>
 *
 * <p>The first three failures are free: people mistype passwords, and punishing that teaches them
 * the system is broken. From the fourth, the wait doubles — 1, 2, 4, 8 seconds and so on — capped
 * at five minutes, because beyond that the delay stops being a deterrent and starts being an
 * outage for whoever actually owns the account.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class LoginRateLimiter {

  /** Failures that cost nothing. Mistyping a password three times is ordinary. */
  private static final int FREE_ATTEMPTS = 3;

  /** The longest wait imposed. Beyond this the limiter harms the owner more than the attacker. */
  private static final Duration MAX_DELAY = Duration.ofMinutes(5);

  /**
   * How long a failure is remembered.
   *
   * <p>Long enough that a slow attack still accumulates, short enough that yesterday's typo does
   * not greet the user today.
   */
  private static final Duration WINDOW = Duration.ofHours(1);

  private final StringRedisTemplate redis;

  /**
   * How long the caller must wait before this login attempt may be evaluated.
   *
   * @param email the address being logged in as, in any casing
   * @param clientIp the caller's address, as resolved from the forwarded-header chain
   * @return the remaining wait, or {@link Duration#ZERO} when the attempt may proceed now
   */
  public Duration retryAfter(String email, String clientIp) {
    Duration byAccount = delayFor(accountKey(email));
    Duration byAddress = delayFor(addressKey(clientIp));
    // The stricter of the two, because an attacker only needs one of them to be lax.
    return byAccount.compareTo(byAddress) >= 0 ? byAccount : byAddress;
  }

  /**
   * Records a failed attempt against both keys.
   *
   * <p>Called for every failure, including one against an address that has no account. Skipping the
   * unknown-address case would make the response time differ between a known and an unknown
   * address, which is the enumeration {@code REQ-SEC-110} closes.
   *
   * @param email the address that was tried
   * @param clientIp the caller's address
   */
  public void recordFailure(String email, String clientIp) {
    bump(accountKey(email));
    bump(addressKey(clientIp));
  }

  /**
   * Clears the counters after a successful login.
   *
   * <p>Only the account's counter and the address that succeeded. An attacker who guesses one
   * password should not thereby clear the penalty they accumulated against every other account.
   *
   * @param email the address that logged in
   * @param clientIp the caller's address
   */
  public void recordSuccess(String email, String clientIp) {
    redis.delete(accountKey(email));
    redis.delete(addressKey(clientIp));
  }

  /**
   * How long the caller must wait before another password reset may be asked for (REQ-SEC-018).
   *
   * <p>Its own counters, not the login ones, and the separation is the point in both directions: a
   * flood of reset requests must not lock the account holder out of signing in, and somebody
   * failing to guess a password must not thereby stop the real owner from asking for a reset.
   *
   * <p>What it protects is the mailbox. A reset endpoint with no throttle is a way to send somebody
   * a hundred messages, and the account it belongs to has no say in whether they arrive.
   *
   * @param email the address a reset was asked for, in any casing
   * @param clientIp the caller's address
   * @return the remaining wait, or {@link Duration#ZERO} when the request may proceed now
   */
  public Duration retryAfterReset(String email, String clientIp) {
    Duration byAccount = delayFor(resetAccountKey(email));
    Duration byAddress = delayFor(resetAddressKey(clientIp));
    return byAccount.compareTo(byAddress) >= 0 ? byAccount : byAddress;
  }

  /**
   * Records that a reset was asked for.
   *
   * <p>Counted whether or not the address has an account, for the reason {@link #recordFailure}
   * gives: a counter that only moved for known addresses would answer the question the endpoint
   * refuses to answer (REQ-SEC-110).
   *
   * @param email the address a reset was asked for
   * @param clientIp the caller's address
   */
  public void recordResetRequest(String email, String clientIp) {
    bump(resetAccountKey(email));
    bump(resetAddressKey(clientIp));
  }

  /**
   * Computes the wait a key's failure count currently imposes.
   *
   * @param key the Valkey key holding the count
   * @return the remaining wait, or zero
   */
  private Duration delayFor(String key) {
    String raw = redis.opsForValue().get(key);
    if (raw == null) {
      return Duration.ZERO;
    }
    long failures = parse(raw);
    if (failures <= FREE_ATTEMPTS) {
      return Duration.ZERO;
    }

    long seconds = 1L << Math.min(failures - FREE_ATTEMPTS - 1, 20);
    Duration required = Duration.ofSeconds(Math.min(seconds, MAX_DELAY.toSeconds()));

    // MILLISECONDS, and that is not a detail. The counter's remaining time to
    // live says how long ago the last failure was -- every failure restarts the
    // window, so elapsed = WINDOW - remaining -- and asking for it in SECONDS
    // rounds the remainder DOWN, which rounds the elapsed time UP by as much as
    // a second. Every step of this delay was therefore up to a second shorter
    // than it says, and the first step, which is exactly one second, could be no
    // delay at all: a failure at 12:00:00.999 and a retry at 12:00:01.001 read
    // as a full second elapsed.
    //
    // `PTTL` costs the same round trip as `TTL` and is exact. Found by
    // `PasswordResetIT.theThrottleEngages` failing in CI on 2026-09-21 while
    // passing on every developer machine -- a ~5 % flake that the extra Valkey
    // round trip of REQ-SEC-064's rate limiter made likely enough to fire.
    Long remainingMillis = redis.getExpire(key, TimeUnit.MILLISECONDS);
    if (remainingMillis == null || remainingMillis < 0) {
      return Duration.ZERO;
    }
    Duration sinceLastFailure = WINDOW.minusMillis(remainingMillis);
    Duration remaining = required.minus(sinceLastFailure);
    return remaining.isNegative() ? Duration.ZERO : remaining;
  }

  /**
   * Increments a counter and restarts its window.
   *
   * @param key the Valkey key
   */
  private void bump(String key) {
    Long count = redis.opsForValue().increment(key);
    redis.expire(key, WINDOW);
    if (count != null && count == FREE_ATTEMPTS + 1L) {
      // Logged once, when the delay starts applying, rather than on every failure:
      // an attacker must not be able to fill the log by failing.
      log.info("Login throttling engaged for key {}", key);
    }
  }

  /**
   * Reads a counter, treating anything unparseable as no failures.
   *
   * @param raw the stored value
   * @return the count, or zero when the value is not a number
   */
  private static long parse(String raw) {
    try {
      return Long.parseLong(raw);
    } catch (NumberFormatException ignored) {
      return 0L;
    }
  }

  /**
   * The key for an account.
   *
   * <p>Lowercased, so the counter cannot be evaded by varying the casing of an address that the
   * lookup treats as one account anyway.
   *
   * @param email the address
   * @return the Valkey key
   */
  private static String accountKey(String email) {
    return "login:fail:account:" + email.toLowerCase(Locale.ROOT);
  }

  /**
   * The key for a client address.
   *
   * @param clientIp the address
   * @return the Valkey key
   */
  private static String addressKey(String clientIp) {
    return "login:fail:address:" + clientIp;
  }

  /**
   * The key for an account's reset requests.
   *
   * @param email the address
   * @return the Valkey key
   */
  private static String resetAccountKey(String email) {
    return "reset:ask:account:" + email.toLowerCase(Locale.ROOT);
  }

  /**
   * The key for a client address's reset requests.
   *
   * @param clientIp the address
   * @return the Valkey key
   */
  private static String resetAddressKey(String clientIp) {
    return "reset:ask:address:" + clientIp;
  }
}
