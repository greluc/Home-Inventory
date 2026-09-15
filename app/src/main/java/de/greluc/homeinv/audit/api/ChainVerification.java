/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.audit.api;

import java.time.Instant;
import java.util.List;

/**
 * Has the log been altered? (REQ-SEC-070, REQ-SEC-096, REQ-SEC-107, ADR-0031, ADR-0046)
 *
 * <p>Two runs, and they answer different questions. The <b>chain</b> run reads one tenant's entries
 * under that tenant's own context and proves them internally consistent. The <b>anchor</b> run is
 * instance-wide and proves that the entries were not removed and re-chained, because an anchor spans
 * every tenant and chains to its predecessor: reproducing one would mean reproducing them all.
 *
 * <p>A failure names which of the two broke, which is the point of splitting them.
 */
public interface ChainVerification {

  /**
   * Verifies the calling tenant's own chain.
   *
   * <p>Starts at the tenant's most recent truncation marker rather than at genesis, so a retention
   * run that removed the oldest entries leaves a chain that still verifies (REQ-SEC-107). An entry
   * missing <b>without</b> a marker is what {@link Outcome#BROKEN} means.
   *
   * @return what the chain says about itself
   */
  ChainResult verifyChain();

  /**
   * Verifies the anchors, instance-wide.
   *
   * <p>Recomputes the Merkle root of every window that has not been marked pruned, and re-chains
   * the anchors. A pruned window keeps its place in the anchor chain and is not recomputed: its
   * entries are gone by a rule somebody wrote down, and expecting them would make honouring
   * REQ-PRIV-010 indistinguishable from an attack.
   *
   * @param from the earliest window to check, inclusive
   * @param to the latest, exclusive
   * @return what the anchors say
   */
  AnchorResult verifyAnchors(Instant from, Instant to);

  /**
   * What a verification run found.
   *
   * @param outcome intact, truncated or broken
   * @param checked how many entries were read
   * @param firstBrokenSeq the sequence number where it first disagreed, or {@code -1} when nothing
   *     did
   * @param detail one English sentence for an operator, naming what disagreed and where
   */
  record ChainResult(Outcome outcome, long checked, long firstBrokenSeq, String detail) {}

  /**
   * What the anchor run found.
   *
   * @param outcome intact or broken; anchors are never truncated, only marked pruned
   * @param checked how many anchors were read
   * @param pruned how many of those were pruned windows, recomputed over nothing by design
   * @param brokenWindows the windows that disagreed, oldest first, empty when none did
   * @param detail one English sentence for an operator
   */
  record AnchorResult(
      Outcome outcome, long checked, long pruned, List<Instant> brokenWindows, String detail) {}

  /** What a run concluded. */
  enum Outcome {
    /** Every hash reproduced. Nothing was altered that this run can see. */
    INTACT,
    /**
     * The chain begins at a recorded truncation and is intact from there.
     *
     * <p>Distinct from {@link #INTACT} and from {@link #BROKEN} on purpose: an operator needs to
     * know that the oldest entries are gone <i>and</i> that their removal was recorded, which is
     * exactly what REQ-PRIV-010's retention produces.
     */
    TRUNCATED,
    /** Something did not reproduce, and no marker explains it. */
    BROKEN
  }
}
