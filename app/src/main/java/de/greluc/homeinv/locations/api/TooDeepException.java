/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.locations.api;

import java.util.UUID;
import lombok.Getter;

/**
 * The tree would exceed its depth limit.
 *
 * <p>A named failure rather than a check constraint violation, because the caller can act on it:
 * the answer is "put this somewhere shallower", not "something went wrong".
 */
@Getter
public class TooDeepException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** The parent that is already as deep as the tree goes. */
  private final transient UUID parentId;

  /** The limit that would be exceeded. */
  private final transient int maxDepth;

  /**
   * Creates the exception.
   *
   * @param parentId the parent at the limit
   * @param maxDepth the depth limit
   */
  public TooDeepException(UUID parentId, int maxDepth) {
    super("Location " + parentId + " is already at the depth limit of " + maxDepth);
    this.parentId = parentId;
    this.maxDepth = maxDepth;
  }
}
