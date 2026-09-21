/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Counts requests per scope in fixed one-minute windows (REQ-SEC-064).
 *
 * <h2>Fixed windows, not a token bucket</h2>
 *
 * <p>A fixed window is one {@code INCR} and, on the first one, one {@code EXPIRE} — which is what
 * makes it correct across instances without a script, a lock or a read-modify-write. Its known
 * weakness is the boundary: a caller can spend a whole window at the end of one and another at the
 * start of the next, so the real short-term ceiling is twice the figure. That is acceptable here
 * because these limits bound a runaway loop rather than meter a paid allowance — the allowance is
 * {@code REQ-TEN-009}, counted monthly, and it is not this.
 *
 * <p>The window also gives the {@code RateLimit} header its three numbers exactly: the limit, what
 * is left, and the second the window resets. A sliding counter would have to invent the third.
 *
 * <h2>When Valkey is gone</h2>
 *
 * <p>It falls back to counting in this process, and 13 §13.6 says so rather than promising
 * otherwise: with <i>n</i> instances the effective limit becomes <i>n</i> times the configured one.
 * That is a deliberate choice of the lesser failure. The alternative — refusing every request
 * because the counter is unreachable — turns a cache outage into a total outage, and Valkey holds
 * sessions and throttles, not data.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class RequestCounters {

  /** How long a window lasts. The {@code RateLimit} headers are written in these terms. */
  static final Duration WINDOW = Duration.ofMinutes(1);

  /** Where the counters live, so the keyspace stays readable in {@code redis-cli}. */
  private static final String PREFIX = "homeinv:rl:";

  private final StringRedisTemplate redis;

  /** The fallback, used only while Valkey is unreachable. */
  private final Map<String, Window> local = new ConcurrentHashMap<>();

  /**
   * Counts one request against a scope and says where that leaves it.
   *
   * @param scope which limit — {@code user}, {@code tenant}, {@code ip}, {@code auth}
   * @param identity whose counter within that scope
   * @param limit how many are allowed in a window
   * @param now the moment of the request
   * @return what to tell the caller, whether or not it is over
   */
  public Decision count(String scope, String identity, int limit, Instant now) {
    long windowStart = now.getEpochSecond() / WINDOW.toSeconds();
    long resetsIn = WINDOW.toSeconds() - now.getEpochSecond() % WINDOW.toSeconds();
    String key = PREFIX + scope + ':' + identity + ':' + windowStart;

    long used = increment(key, windowStart);
    return new Decision(scope, limit, Math.max(0, limit - used), resetsIn, used <= limit);
  }

  /**
   * Adds one to a counter, in Valkey when it answers and in this process when it does not.
   *
   * @param key the counter
   * @param windowStart which window, for the fallback's own bookkeeping
   * @return the count after the increment
   */
  private long increment(String key, long windowStart) {
    try {
      Long count = redis.opsForValue().increment(key);
      if (count != null && count == 1L) {
        // Twice the window, so a counter cannot outlive its own window by a
        // rounding error and cannot accumulate either.
        redis.expire(key, WINDOW.multipliedBy(2));
      }
      return count == null ? 1L : count;
    } catch (DataAccessException unreachable) {
      // Once per window per key would still be one line per request under a
      // sustained outage, so this is debug and the operator learns it from the
      // Valkey health indicator instead (13 §13.6).
      log.debug("Valkey is not answering; rate limiting falls back to this instance", unreachable);
      return local
          .compute(
              key,
              (ignored, existing) ->
                  existing != null && existing.windowStart == windowStart
                      ? existing
                      : new Window(windowStart))
          .hits
          .incrementAndGet();
    }
  }

  /** One in-process counter and the window it belongs to. */
  private static final class Window {

    private final long windowStart;
    private final AtomicLong hits = new AtomicLong();

    private Window(long windowStart) {
      this.windowStart = windowStart;
    }
  }

  /**
   * What one scope says about this request.
   *
   * @param scope the name that goes into the {@code RateLimit} header
   * @param limit how many are allowed in a window
   * @param remaining how many are left, never negative
   * @param resetsInSeconds how long until the window turns over
   * @param allowed whether this request is within the limit
   */
  public record Decision(
      String scope, int limit, long remaining, long resetsInSeconds, boolean allowed) {}
}
