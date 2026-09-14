/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.plugins.application;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * What the operator allows a plugin call to cost (REQ-PLG-007, 09 §9.5).
 *
 * <h2>Why these are the operator's and not the plugin's</h2>
 *
 * <p>A manifest carries {@code resources.timeoutSeconds} and calls it <i>guidance for the
 * operator's limits</i> (09 §9.3), which is exactly what it is. A limit a plugin set for itself
 * would be foreign input deciding how long the core waits for it, and the plugin most in need of a
 * deadline is the one least likely to declare a short one. The manifest's number is therefore read
 * as a request and capped here.
 *
 * <p>The defaults are deliberately small. A plugin is something outside the deployment answering
 * over a network, and every value below is a bound on how much of the core one badly behaved
 * plugin can occupy.
 */
@Getter
@Component
public class PluginRuntimeProperties {

  /** How long a call may take before it is abandoned, in milliseconds. */
  private final int deadlineMillis;

  /** The largest message either direction may carry, in bytes. */
  private final int maxMessageBytes;

  /** How many calls to one plugin may be in flight at once. */
  private final int concurrentCalls;

  /** How long a caller waits for a place in that pool, in milliseconds. */
  private final int bulkheadWaitMillis;

  /** The percentage of failures that opens the breaker, as a whole percent. */
  private final int failureRatePercent;

  /** How many calls the breaker judges by before it is willing to open. */
  private final int slidingWindowSize;

  /** How long the breaker stays open before it lets one call through, in seconds. */
  private final int openStateSeconds;

  /**
   * Reads the configuration, with the defaults 09 §9.5 describes.
   *
   * @param deadlineMillis {@code HOMEINV_PLUGIN_DEADLINE_MS}. Five seconds, which is what the
   *     manifest example asks for; a plugin needing longer is doing something a request should not
   *     wait for
   * @param maxMessageBytes {@code HOMEINV_PLUGIN_MAX_MESSAGE_BYTES}. 8 MiB, the figure named in
   *     09 §9.5. gRPC's own default is 4 MiB, and a rendered label sheet or a scanned image passes
   *     it
   * @param concurrentCalls {@code HOMEINV_PLUGIN_CONCURRENT_CALLS}. The bulkhead: a slow plugin
   *     consumes this many threads and no more, so the rest of the core keeps answering
   * @param bulkheadWaitMillis {@code HOMEINV_PLUGIN_BULKHEAD_WAIT_MS}. Zero would fail instantly
   *     under a burst that is merely busy; a full second would queue callers behind a plugin that
   *     is already the problem
   * @param failureRatePercent {@code HOMEINV_PLUGIN_FAILURE_RATE}. Half the calls failing is not
   *     a bad patch, it is a broken integration
   * @param slidingWindowSize {@code HOMEINV_PLUGIN_WINDOW}. Twenty calls before the rate means
   *     anything: a breaker that opens on the first two failures opens on a restart
   * @param openStateSeconds {@code HOMEINV_PLUGIN_OPEN_SECONDS}. Long enough for a container to
   *     come back, short enough that nobody is waiting on a person to notice
   */
  public PluginRuntimeProperties(
      @Value("${homeinv.plugin.deadline-ms:5000}") int deadlineMillis,
      @Value("${homeinv.plugin.max-message-bytes:8388608}") int maxMessageBytes,
      @Value("${homeinv.plugin.concurrent-calls:8}") int concurrentCalls,
      @Value("${homeinv.plugin.bulkhead-wait-ms:100}") int bulkheadWaitMillis,
      @Value("${homeinv.plugin.failure-rate:50}") int failureRatePercent,
      @Value("${homeinv.plugin.window:20}") int slidingWindowSize,
      @Value("${homeinv.plugin.open-seconds:30}") int openStateSeconds) {
    this.deadlineMillis = deadlineMillis;
    this.maxMessageBytes = maxMessageBytes;
    this.concurrentCalls = concurrentCalls;
    this.bulkheadWaitMillis = bulkheadWaitMillis;
    this.failureRatePercent = failureRatePercent;
    this.slidingWindowSize = slidingWindowSize;
    this.openStateSeconds = openStateSeconds;
  }

  /**
   * How long to wait for this plugin, taking its manifest's request into account.
   *
   * <p>A plugin may ask for <b>less</b> than the operator allows and get it — a resolver that knows
   * it answers in a second says so, and a caller then waits a second rather than five. It may not
   * ask for more: the ceiling is the operator's, and a manifest asking for a minute would be a
   * plugin deciding how long the core holds a request open.
   *
   * @param manifestSeconds {@code spec.resources.timeoutSeconds} from the manifest, or zero when it
   *     says nothing
   * @return the deadline in milliseconds
   */
  public int deadlineFor(int manifestSeconds) {
    if (manifestSeconds <= 0) {
      return deadlineMillis;
    }
    return Math.min(deadlineMillis, manifestSeconds * 1000);
  }
}
