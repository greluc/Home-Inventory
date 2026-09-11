/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.identity.application;

import java.time.Duration;
import lombok.Getter;

/**
 * The login throttle requires a wait before this attempt may be evaluated.
 *
 * <p>Deliberately distinct from {@link InvalidCredentialsException}, even though it is raised
 * during a login: the caller needs to know that waiting will help, and a client that cannot tell
 * "wrong password" from "too fast" will retry immediately and make it worse. It leaks nothing about
 * whether the account exists, because the throttle counts failures for unknown addresses too.
 */
@Getter
public class TooManyAttemptsException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** How long the caller must wait; becomes the {@code Retry-After} header. */
  private final transient Duration retryAfter;

  /**
   * Creates the exception.
   *
   * @param retryAfter the remaining wait
   */
  public TooManyAttemptsException(Duration retryAfter) {
    super("Too many attempts; retry in " + retryAfter.toSeconds() + "s");
    this.retryAfter = retryAfter;
  }
}
