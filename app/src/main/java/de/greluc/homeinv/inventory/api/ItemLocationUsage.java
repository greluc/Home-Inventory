/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.api;

import java.util.UUID;

/**
 * What {@code locations} may ask {@code inventory}.
 *
 * <p>One question: does anything still live here. It exists because the alternative was a query in
 * {@code locations} against {@code inventory.item}, and no block touches another block's schema
 * (REQ-NFR-021, 04 s4.5) - a rule CI enforces over the migrations and ArchUnit enforces over the
 * code. A port is two files; a schema reference is a build failure and, worse, a precedent.
 */
public interface ItemLocationUsage {

  /**
   * Whether a location still holds at least one live item.
   *
   * @param locationId the location
   * @return {@code true} when deleting it would orphan something
   */
  boolean anyItemIn(UUID locationId);
}
