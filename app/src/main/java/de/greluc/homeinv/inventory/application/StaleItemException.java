/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.application;

import java.util.UUID;
import lombok.Getter;

/**
 * The item changed since the version the client last saw.
 *
 * <p>Carries both versions, because the useful thing for a client is not that it lost, but by how
 * much: a UI can re-fetch and show what changed instead of telling the user to try again.
 */
@Getter
public class StaleItemException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** The item concerned. */
  private final transient UUID id;

  /** The version the client believed it was editing. */
  private final transient long expectedVersion;

  /** The version the item actually has. */
  private final transient long actualVersion;

  /**
   * Creates the exception.
   *
   * @param id the item
   * @param expectedVersion the version the client sent
   * @param actualVersion the version stored
   */
  public StaleItemException(UUID id, long expectedVersion, long actualVersion) {
    super("Item " + id + " changed: expected version " + expectedVersion + ", found " + actualVersion);
    this.id = id;
    this.expectedVersion = expectedVersion;
    this.actualVersion = actualVersion;
  }
}
