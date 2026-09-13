/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.idempotency.api;

/**
 * One {@code Idempotency-Key} was spent on two different requests (REQ-API-005).
 *
 * <p>A {@code 409}. The key says "this is the same request as before"; the body says it is not, and
 * only the client knows which of the two it meant. Answering with the earlier result would make a
 * request that changed nothing look like one that had worked, which is worse than refusing.
 *
 * <p>Also raised when the same key arrives at a different endpoint. A key is spent once, not once
 * per path — otherwise a client that generates one key per retry loop could create an item and a
 * location with it and believe both were protected.
 */
public class IdempotencyKeyConflictException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * <p>The message names neither the key nor the earlier body. The key is the caller's own and it
   * knows it; the body belonged to a request that may have carried anything, and repeating it into
   * an error document would hand it to whoever holds the key now.
   */
  public IdempotencyKeyConflictException() {
    super(
        "This Idempotency-Key has already been used for a different request. A key stands for one "
            + "request: use a new key, or send the original body again.");
  }
}
