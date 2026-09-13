/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.catalog.api;

import java.util.UUID;

/**
 * What the blocks that store attributes can tell {@code catalog} about one field's values.
 *
 * <h2>Why the interface lives here and the implementations do not</h2>
 *
 * <p>REQ-CORE-026 makes the final removal of a field show the affected set first, and the affected
 * set is rows in {@code inventory} and {@code locations}. Asking those blocks directly would close a
 * cycle — both already depend on this one for the type version they reference — so {@code catalog}
 * declares the question and they answer it. The dependency still runs one way, and the block that
 * owns the rows is the block that counts them.
 *
 * <p>Every implementation is tenant-scoped by row-level security, like every other read: the caller
 * gets the count for its own tenant and there is no argument that could say otherwise.
 */
public interface AttributeUsage {

  /**
   * How many rows carry a value under this key.
   *
   * @param fieldKey the attribute key
   * @param typeVersionIds the versions whose rows count; a field belongs to one owner's versions and
   *     nothing else should be counted
   * @return the number of rows, zero when none
   */
  long countCarrying(String fieldKey, java.util.Collection<UUID> typeVersionIds);

  /**
   * Removes the values stored under this key, and the rows the side table holds for them.
   *
   * <p>The destructive half of REQ-CORE-026, called only after a person has been shown what
   * {@link #countCarrying} returns. Runs in the caller's transaction, so a removal that fails
   * half-way removes nothing.
   *
   * @param fieldKey the attribute key
   * @param typeVersionIds the versions whose rows are affected
   * @return how many rows were changed
   */
  long strip(String fieldKey, java.util.Collection<UUID> typeVersionIds);
}
