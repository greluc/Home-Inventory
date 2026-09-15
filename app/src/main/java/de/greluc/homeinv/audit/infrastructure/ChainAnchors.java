/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.audit.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes and writes the hourly anchor (REQ-SEC-096, ADR-0031).
 *
 * <p>An anchor is a Merkle root over every audit entry written in one hour, <b>across all
 * tenants</b>, chained to the anchor before it. That is what a per-tenant chain cannot give: whoever
 * can rewrite one tenant's rows can recompute that tenant's chain, but not an anchor that spans
 * every tenant and every hour since.
 *
 * <p>Windows are anchored in order and none is skipped. A gap in the anchors is not itself evidence
 * of tampering, and it is evidence that the evidence is missing — so a run that has fallen behind
 * catches up window by window rather than jumping to the present.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChainAnchors {

  /** What the first anchor of an instance chains from. */
  private static final String GENESIS = "homeinv-anchor-genesis";

  /**
   * How many windows one run will catch up.
   *
   * <p>A bound rather than "all of them": an instance that was down for a month should come back
   * anchoring, not spend an hour of startup on arithmetic. The next run takes the next hundred.
   */
  private static final int MAX_WINDOWS_PER_RUN = 100;

  private final JdbcClient jdbc;

  /**
   * Anchors every whole hour that has passed and has no anchor yet.
   *
   * <p>The current hour is left alone: entries are still being written into it, and an anchor over
   * a window that is not finished would disagree with itself an hour later.
   *
   * @param now the clock's idea of the present
   * @return how many windows were anchored
   */
  @Transactional
  public int anchorDueWindows(Instant now) {
    Instant currentWindow = now.truncatedTo(ChronoUnit.HOURS);
    Instant next = nextWindowToAnchor(currentWindow);

    int written = 0;
    while (next.isBefore(currentWindow) && written < MAX_WINDOWS_PER_RUN) {
      anchorWindow(next);
      next = next.plus(1, ChronoUnit.HOURS);
      written++;
    }
    if (written > 0) {
      log.info("Anchored {} audit window(s), up to {}", written, next);
    }
    return written;
  }

  /**
   * Which window the next anchor is for.
   *
   * <p>The hour after the newest anchor, or — on an instance that has never anchored — the hour of
   * the oldest audit entry. Starting at the oldest entry rather than at the present means the first
   * run covers what is already there instead of declaring it unanchorable.
   *
   * @param currentWindow the hour now in progress
   * @return the window to anchor next
   */
  private Instant nextWindowToAnchor(Instant currentWindow) {
    Optional<Instant> newest =
        jdbc.sql("select max(window_start) from audit.chain_anchor")
            .query((ResultSet rs, int row) -> rs.getObject(1, java.time.OffsetDateTime.class))
            .optional()
            .filter(java.util.Objects::nonNull)
            .map(java.time.OffsetDateTime::toInstant);
    if (newest.isPresent()) {
      return newest.get().plus(1, ChronoUnit.HOURS);
    }

    // No anchors yet. The oldest entry's hour, or the current one on an instance
    // where nothing has happened at all.
    //
    // Through the SECURITY DEFINER function, because this run has no tenant
    // context: reading `min` directly under row-level security returns nothing,
    // and a run that concludes there is nothing to anchor leaves a log with
    // entries and no anchors.
    return jdbc
        .sql("select audit.oldest_entry_at()")
        .query((ResultSet rs, int row) -> rs.getObject(1, java.time.OffsetDateTime.class))
        .optional()
        .filter(java.util.Objects::nonNull)
        .map(oldest -> oldest.toInstant().truncatedTo(ChronoUnit.HOURS))
        .orElse(currentWindow);
  }

  /**
   * Writes the anchor for one window.
   *
   * @param windowStart the hour, truncated
   */
  private void anchorWindow(Instant windowStart) {
    Instant windowEnd = windowStart.plus(1, ChronoUnit.HOURS);

    // Across every tenant, which row-level security correctly forbids the
    // application — so this goes through the one SECURITY DEFINER function that
    // returns hashes and nothing else.
    List<byte[]> hashes =
        jdbc.sql("select entry_hash from audit.entry_hashes_in(?, ?)")
            .param(java.sql.Timestamp.from(windowStart))
            .param(java.sql.Timestamp.from(windowEnd))
            .query((ResultSet rs, int row) -> rs.getBytes(1))
            .list();

    byte[] root = MerkleRoot.of(hashes);
    byte[] previous = newestAnchorHash();
    byte[] anchorHash = anchorHash(previous, windowStart, windowEnd, root, hashes.size());

    jdbc.sql(
            """
            insert into audit.chain_anchor
                (window_start, window_end, merkle_root, entry_count, prev_hash, anchor_hash)
            values (?, ?, ?, ?, ?, ?)
            on conflict (window_start) do nothing
            """)
        .param(java.sql.Timestamp.from(windowStart))
        .param(java.sql.Timestamp.from(windowEnd))
        .param(root)
        .param((long) hashes.size())
        .param(previous)
        .param(anchorHash)
        .update();
  }

  /**
   * The hash of the newest anchor, or the genesis value.
   *
   * @return 32 bytes
   */
  byte[] newestAnchorHash() {
    return jdbc
        .sql("select anchor_hash from audit.chain_anchor order by window_start desc limit 1")
        .query((ResultSet rs, int row) -> rs.getBytes(1))
        .optional()
        .orElseGet(() -> sha256(GENESIS.getBytes(StandardCharsets.UTF_8)));
  }

  /**
   * What an anchor hashes over.
   *
   * <p>Its predecessor, its window, its root and its count. The count is in there so that an anchor
   * cannot be made to describe a different number of entries than it was computed over — the figure
   * an operator reads beside it.
   *
   * @param previous the predecessor's hash
   * @param windowStart the window's start
   * @param windowEnd its end
   * @param root the Merkle root
   * @param count how many entries were in it
   * @return the anchor's own hash
   */
  static byte[] anchorHash(
      byte[] previous, Instant windowStart, Instant windowEnd, byte[] root, int count) {
    String canonical =
        java.util.HexFormat.of().formatHex(previous)
            + "\n"
            + windowStart
            + "\n"
            + windowEnd
            + "\n"
            + java.util.HexFormat.of().formatHex(root)
            + "\n"
            + count;
    return sha256(canonical.getBytes(StandardCharsets.UTF_8));
  }

  private static byte[] sha256(byte[] input) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(input);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is part of every JRE", impossible);
    }
  }
}
