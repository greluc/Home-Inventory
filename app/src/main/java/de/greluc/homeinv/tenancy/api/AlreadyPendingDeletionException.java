/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tenancy.api;

import java.time.Instant;
import lombok.Getter;

/**
 * The tenant has already been asked to be erased (REQ-TEN-011).
 *
 * <p>Answered as {@code 409} carrying when the erasure begins, so a client can say "already
 * requested, and it happens on the 12th" rather than "something went wrong". Not a second token: two
 * live revocations would be two ways to withdraw one request, and withdrawing with the one somebody
 * remembers would leave the other working.
 */
@Getter
public class AlreadyPendingDeletionException extends RuntimeException {

  /** When the grace period runs out. */
  private final Instant eraseAfter;

  /**
   * @param eraseAfter when the erasure begins
   */
  public AlreadyPendingDeletionException(Instant eraseAfter) {
    super("This tenant has already been asked to be erased.");
    this.eraseAfter = eraseAfter;
  }
}
