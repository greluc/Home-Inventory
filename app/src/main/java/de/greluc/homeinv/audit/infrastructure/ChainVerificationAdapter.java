/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.audit.infrastructure;

import de.greluc.homeinv.audit.api.AuditLog;
import de.greluc.homeinv.audit.api.ChainVerification;
import de.greluc.homeinv.platform.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Recomputes what the log says about itself (REQ-SEC-070, REQ-SEC-096, REQ-SEC-107).
 *
 * <p>Both runs rebuild hashes from the stored rows with the same code that wrote them — {@link
 * AuditLogAdapter#hash} and {@link ChainAnchors#anchorHash}. A second implementation of either
 * would be a second answer, and the first thing it would disagree with is the truth.
 */
@Component
@RequiredArgsConstructor
public class ChainVerificationAdapter implements ChainVerification {

  private final JdbcClient jdbc;
  private final ObjectMapper json;

  @Override
  @Transactional(readOnly = true)
  public ChainResult verifyChain() {
    UUID tenantId = TenantContext.require();
    Optional<Truncation> truncation = newestTruncation();

    List<Row> rows =
        jdbc.sql(
                """
                select seq, occurred_at, actor_kind, actor_id, actor_label, action, resource_type,
                       resource_id, diff::text as diff, ip_hash, client, correlation_id,
                       prev_hash, entry_hash
                from audit.audit_entry
                where tenant_id = ? and seq >= ?
                order by seq
                """)
            .param(tenantId)
            .param(truncation.map(Truncation::oldestSeq).orElse(1L))
            .query(ChainVerificationAdapter::toRow)
            .list();

    // Without a marker the chain starts at the tenant's genesis. With one it
    // starts at the oldest RETAINED entry, whose own hash the marker pins
    // (ADR-0046) — that entry's `prev_hash` names a predecessor a retention run
    // removed on purpose, so it is the one place the chain is not followed
    // backwards.
    byte[] expectedPrevious =
        truncation.isPresent() ? null : AuditLogAdapter.genesisOf(tenantId);

    long previousSeq = truncation.map(Truncation::oldestSeq).map(seq -> seq - 1).orElse(0L);
    boolean first = true;
    for (Row row : rows) {
      if (first && truncation.isPresent()) {
        if (!java.util.Arrays.equals(row.entryHash(), truncation.get().oldestHash())) {
          return new ChainResult(
              Outcome.BROKEN,
              rows.size(),
              row.seq(),
              "The truncation marker names entry "
                  + truncation.get().oldestSeq()
                  + " as the oldest retained one, and that entry is not the one it describes.");
        }
        expectedPrevious = row.prevHash();
      }
      first = false;

      if (row.seq() != previousSeq + 1) {
        return new ChainResult(
            Outcome.BROKEN,
            rows.size(),
            row.seq(),
            "The chain jumps from " + previousSeq + " to " + row.seq()
                + " and no truncation marker accounts for it.");
      }
      if (!java.util.Arrays.equals(row.prevHash(), expectedPrevious)) {
        return new ChainResult(
            Outcome.BROKEN,
            rows.size(),
            row.seq(),
            "Entry " + row.seq() + " names a predecessor that is not the entry before it.");
      }
      byte[] recomputed =
          AuditLogAdapter.hashOf(
              row.prevHash(),
              tenantId,
              row.seq(),
              row.occurredAt(),
              row.asEntry(),
              AuditLogAdapter.canonicalJsonOf(json, row.diff()),
              row.ipHash());
      if (!java.util.Arrays.equals(recomputed, row.entryHash())) {
        return new ChainResult(
            Outcome.BROKEN,
            rows.size(),
            row.seq(),
            "Entry " + row.seq() + " does not hash to what is stored beside it: it was altered.");
      }
      expectedPrevious = row.entryHash();
      previousSeq = row.seq();
    }

    if (truncation.isPresent()) {
      return new ChainResult(
          Outcome.TRUNCATED,
          rows.size(),
          -1,
          "Intact from entry "
              + truncation.get().oldestSeq()
              + ", where a "
              + truncation.get().reason()
              + " run removed "
              + truncation.get().removedCount()
              + " older entries on "
              + truncation.get().truncatedAt()
              + ".");
    }
    return new ChainResult(
        Outcome.INTACT, rows.size(), -1, "Intact from this tenant's first entry.");
  }

  @Override
  @Transactional(readOnly = true)
  public AnchorResult verifyAnchors(Instant from, Instant to) {
    List<Anchor> anchors =
        jdbc.sql(
                """
                select window_start, window_end, merkle_root, entry_count, prev_hash,
                       anchor_hash, pruned
                from audit.chain_anchor
                where window_start >= ? and window_start < ?
                order by window_start
                """)
            .param(java.sql.Timestamp.from(from))
            .param(java.sql.Timestamp.from(to))
            .query(ChainVerificationAdapter::toAnchor)
            .list();

    List<Instant> broken = new ArrayList<>();
    long pruned = 0;
    byte[] expectedPrevious = null;

    for (Anchor anchor : anchors) {
      if (expectedPrevious != null && !java.util.Arrays.equals(anchor.prevHash(), expectedPrevious)) {
        broken.add(anchor.windowStart());
        expectedPrevious = anchor.anchorHash();
        continue;
      }

      byte[] recomputedHash =
          ChainAnchors.anchorHash(
              anchor.prevHash(),
              anchor.windowStart(),
              anchor.windowEnd(),
              anchor.merkleRoot(),
              (int) anchor.entryCount());
      if (!java.util.Arrays.equals(recomputedHash, anchor.anchorHash())) {
        broken.add(anchor.windowStart());
      } else if (anchor.pruned()) {
        // Its entries are gone by a rule somebody wrote down. Recomputing the
        // root over what is left would report tampering every day the retention
        // run does its job (ADR-0046).
        pruned++;
      } else {
        List<byte[]> hashes =
            jdbc.sql("select entry_hash from audit.entry_hashes_in(?, ?)")
                .param(java.sql.Timestamp.from(anchor.windowStart()))
                .param(java.sql.Timestamp.from(anchor.windowEnd()))
                .query((ResultSet rs, int row) -> rs.getBytes(1))
                .list();
        if (hashes.size() != anchor.entryCount()
            || !java.util.Arrays.equals(MerkleRoot.of(hashes), anchor.merkleRoot())) {
          broken.add(anchor.windowStart());
        }
      }
      expectedPrevious = anchor.anchorHash();
    }

    if (broken.isEmpty()) {
      return new AnchorResult(
          Outcome.INTACT,
          anchors.size(),
          pruned,
          List.of(),
          "Every anchor in the period reproduces, and the anchor chain is unbroken.");
    }
    return new AnchorResult(
        Outcome.BROKEN,
        anchors.size(),
        pruned,
        List.copyOf(broken),
        broken.size()
            + " window(s) do not reproduce, the first at "
            + broken.getFirst()
            + ". Entries were removed or altered without a truncation marker.");
  }

  /**
   * The newest truncation marker of the calling tenant.
   *
   * @return the marker, or empty when the chain has never been truncated
   */
  private Optional<Truncation> newestTruncation() {
    return jdbc
        .sql(
            """
            select oldest_seq, oldest_hash, removed_count, reason, truncated_at
            from audit.chain_truncation
            where tenant_id = ?
            order by truncated_at desc limit 1
            """)
        .param(TenantContext.require())
        .query(
            (ResultSet rs, int row) ->
                new Truncation(
                    rs.getLong("oldest_seq"),
                    rs.getBytes("oldest_hash"),
                    rs.getLong("removed_count"),
                    rs.getString("reason"),
                    rs.getObject("truncated_at", java.time.OffsetDateTime.class).toInstant()))
        .optional();
  }

  private static Row toRow(ResultSet rs, int rowNumber) throws SQLException {
    return new Row(
        rs.getLong("seq"),
        rs.getObject("occurred_at", java.time.OffsetDateTime.class).toInstant(),
        AuditLog.ActorKind.valueOf(rs.getString("actor_kind")),
        rs.getObject("actor_id", UUID.class),
        rs.getString("actor_label"),
        rs.getString("action"),
        rs.getString("resource_type"),
        rs.getObject("resource_id", UUID.class),
        rs.getString("diff"),
        rs.getBytes("ip_hash"),
        rs.getString("client"),
        rs.getString("correlation_id"),
        rs.getBytes("prev_hash"),
        rs.getBytes("entry_hash"));
  }

  private static Anchor toAnchor(ResultSet rs, int rowNumber) throws SQLException {
    return new Anchor(
        rs.getObject("window_start", java.time.OffsetDateTime.class).toInstant(),
        rs.getObject("window_end", java.time.OffsetDateTime.class).toInstant(),
        rs.getBytes("merkle_root"),
        rs.getLong("entry_count"),
        rs.getBytes("prev_hash"),
        rs.getBytes("anchor_hash"),
        rs.getBoolean("pruned"));
  }

  /**
   * One stored entry, as the verification reads it.
   *
   * @param seq its sequence number
   * @param occurredAt when
   * @param actorKind what kind of actor
   * @param actorId the person, or {@code null}
   * @param actorLabel the plugin or task, or {@code null}
   * @param action what was done
   * @param resourceType what kind of thing
   * @param resourceId which one
   * @param diff what changed, as stored
   * @param ipHash the address's digest, which the chain covers and a retention run never touches
   * @param client what made the request
   * @param correlationId the trace
   * @param prevHash what it says its predecessor was
   * @param entryHash what it says it hashes to
   */
  private record Row(
      long seq,
      Instant occurredAt,
      AuditLog.ActorKind actorKind,
      UUID actorId,
      String actorLabel,
      String action,
      String resourceType,
      UUID resourceId,
      String diff,
      byte[] ipHash,
      String client,
      String correlationId,
      byte[] prevHash,
      byte[] entryHash) {

    /**
     * The entry as {@link AuditLogAdapter#hashOf} expects it.
     *
     * <p>The address is passed as {@code null} and the digest goes in separately: the stored row no
     * longer has the address once a retention run has cleared it, and the hash was never over the
     * address itself for exactly that reason.
     *
     * @return the entry
     */
    AuditLog.NewEntry asEntry() {
      return new AuditLog.NewEntry(
          actorKind, actorId, actorLabel, action, resourceType, resourceId,
          Map.of(), null, client, correlationId);
    }
  }

  /**
   * One anchor, as the verification reads it.
   *
   * @param windowStart the hour
   * @param windowEnd its end
   * @param merkleRoot the root over the window's entries
   * @param entryCount how many there were
   * @param prevHash the anchor before it
   * @param anchorHash its own hash
   * @param pruned whether a retention run removed the window's entries
   */
  private record Anchor(
      Instant windowStart,
      Instant windowEnd,
      byte[] merkleRoot,
      long entryCount,
      byte[] prevHash,
      byte[] anchorHash,
      boolean pruned) {}

  /**
   * Where a tenant's verifiable chain begins.
   *
   * @param oldestSeq the oldest retained entry
   * @param oldestHash its hash, which the chain is checked from
   * @param removedCount how many were removed
   * @param reason {@code retention} or {@code tenant-erasure}
   * @param truncatedAt when
   */
  private record Truncation(
      long oldestSeq, byte[] oldestHash, long removedCount, String reason, Instant truncatedAt) {}
}
