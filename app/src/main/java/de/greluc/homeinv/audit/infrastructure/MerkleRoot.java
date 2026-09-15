/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.audit.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * The Merkle root over an hour of audit entries (REQ-SEC-096).
 *
 * <p>A tree and not a hash of the concatenation, because the two differ in what they can be asked
 * afterwards: a root lets one entry's membership be proved against it without the rest of the
 * window, which is what makes an anchor useful to somebody holding a single entry.
 *
 * <h2>The odd node is promoted, not duplicated</h2>
 *
 * <p>Duplicating the last node of an odd level is the older convention and it makes two different
 * lists produce the same root — the flaw Bitcoin carries as CVE-2012-2459. A window of audit
 * entries is exactly the kind of list somebody would want to forge that way, so the odd node moves
 * up a level unchanged instead.
 */
final class MerkleRoot {

  /**
   * The root of a window in which nothing happened.
   *
   * <p>Empty windows are anchored: an hour with no entries is a fact about the log, and skipping it
   * would leave a gap in the anchor chain indistinguishable from a run that never happened.
   */
  private static final String EMPTY = "homeinv-anchor-empty-window";

  private MerkleRoot() {
    throw new AssertionError("A calculation, not a thing to instantiate");
  }

  /**
   * Computes the root.
   *
   * @param leaves the entry hashes, in the order the anchor is defined over — by tenant, then by
   *     that tenant's sequence. A different order is a different root, which is why the ordering
   *     lives in the SQL function rather than in a caller
   * @return the 32-byte root
   */
  static byte[] of(List<byte[]> leaves) {
    if (leaves.isEmpty()) {
      return sha256(EMPTY.getBytes(StandardCharsets.UTF_8));
    }

    List<byte[]> level = new ArrayList<>(leaves);
    while (level.size() > 1) {
      List<byte[]> parents = new ArrayList<>((level.size() + 1) / 2);
      for (int index = 0; index + 1 < level.size(); index += 2) {
        parents.add(pair(level.get(index), level.get(index + 1)));
      }
      if (level.size() % 2 == 1) {
        // Promoted unchanged. Hashing it with itself is what makes two different
        // lists collide.
        parents.add(level.getLast());
      }
      level = parents;
    }
    return level.getFirst();
  }

  /**
   * One parent node.
   *
   * @param left the left child
   * @param right the right child
   * @return the SHA-256 of the two, in that order
   */
  private static byte[] pair(byte[] left, byte[] right) {
    MessageDigest digest = digest();
    digest.update(left);
    digest.update(right);
    return digest.digest();
  }

  private static byte[] sha256(byte[] input) {
    return digest().digest(input);
  }

  private static MessageDigest digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is part of every JRE", impossible);
    }
  }
}
