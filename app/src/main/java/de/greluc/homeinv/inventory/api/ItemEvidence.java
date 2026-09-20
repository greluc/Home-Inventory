/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The files that back an item up, for a report that has to carry them (REQ-LIFE-016).
 *
 * <p>Declared here and implemented by {@code media}, which is the inversion this codebase uses
 * everywhere a block needs something another block owns. {@code media} already depends on {@code
 * inventory} — it listens for {@code ItemPurged} to let go of what an item held — so a dependency
 * the other way would close a cycle and the modularity check would fail the build (ADR-0002).
 *
 * <p>In <b>bulk</b>, keyed by item: an insurance report of two hundred things asking per item would
 * be two hundred round trips for a document somebody prints once.
 */
public interface ItemEvidence {

  /**
   * What backs each of these items up.
   *
   * <p>Only what the scanner has cleared: an attachment still waiting for a verdict, or one it
   * refused, is left out rather than offered. A report is not the place to learn that a file was
   * infected.
   *
   * @param itemIds the items
   * @return the attachments per item, in the order a reader should see them — the primary
   *     photograph first, then receipts, then warranty proofs. An item with nothing attached is
   *     absent from the map rather than present with an empty list
   */
  Map<UUID, List<Attached>> forItems(Collection<UUID> itemIds);

  /**
   * One attachment.
   *
   * @param mediaObjectId which file
   * @param mediaType what it is
   * @param byteSize how large
   * @param role what the tenant said it was for — {@code PHOTO}, {@code RECEIPT}, {@code
   *     WARRANTY_PROOF} or {@code OTHER}
   * @param primaryImage whether it is the image lists show
   * @param sha256 its content address, which is how the bytes are fetched when a document needs
   *     them rather than a reference to them
   */
  record Attached(
      UUID mediaObjectId,
      String mediaType,
      long byteSize,
      String role,
      boolean primaryImage,
      String sha256) {}
}
