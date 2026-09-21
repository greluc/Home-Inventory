/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import java.time.Duration;
import lombok.Getter;

/**
 * A request refused because a limit of {@code REQ-SEC-064} was reached.
 *
 * <p>Distinct from {@code TooManyAttemptsException}, which is the login throttle of
 * {@code REQ-SEC-012} and grows a delay after repeated <b>failures</b>. This one counts requests
 * rather than failures and does not care whether they succeeded. Both answer {@code 429} with the
 * same problem type, because which limit was reached is an operational detail and not a contract —
 * what a client does about either is identical: wait the {@code Retry-After} and come back.
 */
@Getter
public class RateLimitedException extends RuntimeException {

  /** How long to wait, which becomes the {@code Retry-After} header. */
  private final transient Duration retryAfter;

  /** Which limit was reached, for the log line and for the operator's metric. */
  private final transient String scope;

  /**
   * Builds the refusal.
   *
   * @param scope which limit was reached
   * @param retryAfter how long until the window turns over
   */
  public RateLimitedException(String scope, Duration retryAfter) {
    super("Rate limit reached for scope " + scope);
    this.scope = scope;
    this.retryAfter = retryAfter;
  }
}
