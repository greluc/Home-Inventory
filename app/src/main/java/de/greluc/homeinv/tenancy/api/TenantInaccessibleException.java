/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tenancy.api;

/**
 * The tenant is suspended or waiting to be erased, and answers nothing (O26 in ADR-0000).
 *
 * <p>Answered as {@code 403} with one token, {@code tenant-inaccessible}, for <b>both</b> states.
 * Two tokens would say which state a tenant is in, and that is a status oracle over a boundary
 * REQ-SEC-025 closes deliberately — the same reason the {@code not-found} token must not be split.
 * A member sees the state in the administration view, where they are authenticated and entitled to
 * it.
 *
 * <p>{@code 404} was rejected because the caller is a member and the tenant's existence is no secret
 * from them; {@code 503} because suspension is not transient and a {@code Retry-After} would be a
 * lie.
 */
public class TenantInaccessibleException extends RuntimeException {

  /** Creates the refusal. */
  public TenantInaccessibleException() {
    super("This tenant is not available. Its owner can see why.");
  }
}
