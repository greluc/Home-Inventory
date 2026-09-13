/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.platform;

import java.util.OptionalLong;
import java.util.UUID;

/**
 * The one comparison behind {@code If-Match} (REQ-API-004).
 *
 * <p>In {@code platform} because every block that owns an editable resource does the same thing
 * with it, and because the alternative — each block writing its own three lines — is three chances
 * to compare the wrong way round.
 */
public final class Versions {

  private Versions() {}

  /**
   * Refuses a write whose caller was looking at an older version.
   *
   * <p>An absent expectation passes. That is not a hole in {@code REQ-API-004}: the HTTP surface
   * <em>requires</em> the header and answers {@code 428} without one, so an absent expectation here
   * means the caller is not a client at all — a background run, a migration of data, a test — and
   * none of those is working from a screen that can have gone stale.
   *
   * @param what the kind of resource, for the message
   * @param id which one
   * @param expected what the caller believed the version was, if anything
   * @param current what it actually is
   * @throws StaleVersionException when the two differ
   */
  public static void requireCurrent(String what, UUID id, OptionalLong expected, long current) {
    if (expected.isPresent() && expected.getAsLong() != current) {
      throw new StaleVersionException(what, id, expected.getAsLong(), current);
    }
  }
}
