/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.portability.api;

import java.util.List;
import java.util.UUID;

/**
 * Putting the tags a CSV names onto an item (REQ-PORT-001).
 *
 * <p>Tags arrive as names, because that is what another system exported. A name that is not a tag
 * here becomes one: an import that dropped the tags would lose the one piece of organisation the
 * person doing the importing is most likely to care about, and an import that refused unknown ones
 * would refuse the whole file.
 *
 * <p>Implemented by {@code tagging}, for the reason {@link PlacePath} gives.
 */
public interface TagIngest {

  /**
   * Puts these tags on an item, creating any that do not exist.
   *
   * <p>Idempotent: a tag already on the item is left alone, so importing the same file twice does
   * not produce two of anything.
   *
   * @param itemId what to tag
   * @param names the tag names, blank ones ignored
   * @param actor who is importing
   * @return how many tags were created rather than found
   */
  int assign(UUID itemId, List<String> names, UUID actor);
}
