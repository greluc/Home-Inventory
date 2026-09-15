/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.audit.infrastructure;

import de.greluc.homeinv.audit.api.AuditLog;
import de.greluc.homeinv.audit.api.AuditTrail;
import de.greluc.homeinv.platform.CursorCodec;
import de.greluc.homeinv.platform.Page;
import de.greluc.homeinv.platform.TenantContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes and reads {@code audit.audit_entry}, chained per tenant (ADR-0031).
 *
 * <h2>The lock, and why it is taken once</h2>
 *
 * <p>Computing {@code prev_hash} means reading this tenant's last entry and being certain nobody
 * writes between that read and the insert — a lock. It is a per-tenant advisory lock held to the
 * end of the transaction, so tenants never block one another.
 *
 * <p>A transaction writing several entries takes it <b>once</b> (REQ-SEC-070). That is not an
 * optimisation of the lock itself — an advisory lock re-taken in the same transaction blocks
 * nobody — but of the read beside it: the tail of the chain is remembered for the transaction, so
 * an import writing a thousand entries does one read rather than a thousand.
 *
 * <h2>The hash, and what it is over</h2>
 *
 * <p>SHA-256 over a canonical text form of every column that carries meaning, newline-separated,
 * with the predecessor's hash first. Computed <b>here</b> and not in SQL, because a verification run
 * has to reproduce it exactly and two implementations of one algorithm are two answers waiting to
 * disagree.
 *
 * <p>{@code occurred_at} is therefore assigned here too. Letting the database default fill it would
 * mean hashing a value this side never saw.
 */
@Component
@RequiredArgsConstructor
public class AuditLogAdapter implements AuditLog {

  /** The cap REQ-NFR-010 puts on every page of every collection. */
  private static final int MAX_PAGE = 200;

  /** What an audit cursor is bound to. */
  private static final String CURSOR = "audit";

  /**
   * The advisory-lock namespace.
   *
   * <p>The two-argument form, so an audit lock cannot collide with another mechanism that happens
   * to hash a tenant id to the same number. Arbitrary and fixed.
   */
  private static final int LOCK_NAMESPACE = 8271;

  /** What the first entry of a tenant chains from, before the tenant id is folded in. */
  private static final String GENESIS = "homeinv-audit-genesis:";

  private static final String INSERT =
      """
      insert into audit.audit_entry (tenant_id, seq, occurred_at, actor_kind, actor_id,
                                     actor_label, action, resource_type, resource_id, diff,
                                     ip, ip_hash, client, correlation_id, prev_hash, entry_hash)
      values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::inet, ?, ?, ?, ?, ?)
      """;

  private static final String COLUMNS =
      """
      select seq, occurred_at, actor_kind, actor_id, actor_label, action, resource_type,
             resource_id, diff::text as diff, host(ip) as ip, client, correlation_id
      from audit.audit_entry
      """;

  /** How a month is named in {@link #knownMonths}. */
  private static final java.time.format.DateTimeFormatter MONTH =
      java.time.format.DateTimeFormatter.ofPattern("yyyy-MM");

  private final JdbcClient jdbc;
  private final CursorCodec cursors;
  private final ObjectMapper json;

  /** The months this process has already made sure of. */
  private final java.util.Set<String> knownMonths = java.util.concurrent.ConcurrentHashMap.newKeySet();

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public long record(String action, String resourceType, UUID resourceId, Map<String, Object> diff) {
    AuditTrail.Origin origin =
        AuditTrail.current()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "No audit origin is established for "
                            + action
                            + ". Every mutating path runs inside a boundary that knows who is"
                            + " acting (REQ-SEC-068); one that does not would record an action"
                            + " nobody can be held to."));
    long seq =
        record(
            new NewEntry(
                origin.actorKind(),
                origin.actorId(),
                origin.actorLabel(),
                action,
                resourceType,
                resourceId,
                diff,
                origin.ip(),
                origin.client(),
                origin.correlationId()));
    AuditTrail.recorded();
    return seq;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public long record(NewEntry entry) {
    UUID tenantId = TenantContext.require();
    Tail tail = tailOf(tenantId);

    long seq = tail.seq() + 1;
    // Truncated to microseconds, which is what `timestamptz` keeps. Hashing a
    // nanosecond the column cannot store would mean the value read back never
    // reproduces its own hash, and every verification run would report tampering.
    Instant occurredAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    String diff = canonicalJson(entry.diff());
    byte[] entryHash = hash(tail.hash(), tenantId, seq, occurredAt, entry, diff);

    ensurePartition(occurredAt);
    write(tenantId, seq, occurredAt, entry, diff, tail.hash(), entryHash);
    bindTail(tenantId, new Tail(seq, entryHash));
    return seq;
  }

  @Override
  @Transactional(readOnly = true)
  public Page<AuditView> actions(
      UUID actorId, Instant from, Instant to, String cursor, int limit) {
    UUID tenantId = TenantContext.require();
    int size = Math.clamp(limit, 1, MAX_PAGE);

    // Four shapes of one query rather than a builder: the two questions a caller
    // asks (one actor or all) times the two positions (first page or a cursor),
    // each with its parameters in a fixed order. A StringBuilder here would be
    // dynamic SQL, which this project routes through a checked allowlist or not
    // at all (CLAUDE.md).
    String actorClause = actorId == null ? "" : " and actor_id = ?";
    String cursorClause = cursor == null || cursor.isBlank() ? "" : " and seq < ?";
    String sql =
        COLUMNS
            + " where tenant_id = ? and occurred_at >= ? and occurred_at < ?"
            + actorClause
            + cursorClause
            // Newest first, and ordered by `seq` rather than by time: two entries
            // of one tenant can share a microsecond, and the sequence orders them
            // without a tie-break nobody can reproduce.
            + " order by seq desc limit ?";

    JdbcClient.StatementSpec spec =
        jdbc.sql(sql)
            .param(tenantId)
            .param(java.sql.Timestamp.from(from))
            .param(java.sql.Timestamp.from(to));
    if (actorId != null) {
      spec = spec.param(actorId);
    }
    if (!cursorClause.isEmpty()) {
      // The position is a sequence number, which is already an ordered key of its
      // own — the cursor still carries the signed pair, so a tampered one is
      // refused by the same code path as everywhere else (REQ-SEC-106).
      spec = spec.param(cursors.decode(cursor, CURSOR).createdAt().toEpochMilli());
    }
    List<AuditView> rows = spec.param(size).query(AuditLogAdapter::toView).list();

    String next =
        rows.size() == size
            ? cursors.encode(
                CursorCodec.Position.of(
                    Instant.ofEpochMilli(rows.getLast().seq()), tenantId),
                CURSOR)
            : null;
    return Page.of(rows, next);
  }

  /**
   * The tail of this tenant's chain, taking the lock on the first call of the transaction.
   *
   * @param tenantId whose chain
   * @return the last sequence number and hash, or the genesis pair for an empty chain
   */
  private Tail tailOf(UUID tenantId) {
    Tail bound = boundTail(tenantId);
    if (bound != null) {
      return bound;
    }

    // Held to the end of the transaction, which is what makes the read below and
    // the insert after it one step as far as any other writer is concerned.
    // `listOfRows` and not a typed query: the function returns `void`, and asking
    // the driver for an `Integer` asks it to convert nothing into a number.
    jdbc.sql("select pg_advisory_xact_lock(?, hashtext(?))")
        .param(LOCK_NAMESPACE)
        .param(tenantId.toString())
        .query()
        .listOfRows();

    Tail tail =
        jdbc
            .sql("select seq, entry_hash from audit.audit_entry"
                + " where tenant_id = ? order by seq desc limit 1")
            .param(tenantId)
            .query((ResultSet rs, int row) -> new Tail(rs.getLong("seq"), rs.getBytes("entry_hash")))
            .optional()
            .orElseGet(() -> new Tail(0L, genesisOf(tenantId)));
    bindTail(tenantId, tail);
    return tail;
  }

  /**
   * Makes sure the month this entry falls in has a partition.
   *
   * <p>Asked <b>before</b> the insert and not after a failure. PostgreSQL reports a missing
   * partition as a check violation, indistinguishable by class from a real constraint failure — and
   * by the time it arrives the transaction is aborted, so the recovery would have to happen in a
   * transaction that no longer accepts statements. A first write of a month costs one extra
   * statement; every write after it costs nothing, because the month is remembered here.
   *
   * <p>The set is per process and never pruned: twelve entries a year, and a stale one would only
   * mean asking a function that answers instantly.
   *
   * @param occurredAt when the entry happened
   */
  private void ensurePartition(Instant occurredAt) {
    String month = occurredAt.atZone(java.time.ZoneOffset.UTC).format(MONTH);
    if (knownMonths.contains(month)) {
      return;
    }
    jdbc.sql("select audit.ensure_audit_partition(?)")
        .param(java.sql.Timestamp.from(occurredAt))
        .query()
        .listOfRows();
    knownMonths.add(month);
  }

  private void write(
      UUID tenantId,
      long seq,
      Instant occurredAt,
      NewEntry entry,
      String diff,
      byte[] prevHash,
      byte[] entryHash) {
    jdbc.sql(INSERT)
        .param(tenantId)
        .param(seq)
        .param(java.sql.Timestamp.from(occurredAt))
        .param(entry.actorKind().name())
        .param(entry.actorId())
        .param(entry.actorLabel())
        .param(entry.action())
        .param(entry.resourceType())
        .param(entry.resourceId())
        .param(diff)
        .param(entry.ip())
        .param(addressHash(entry.ip()))
        .param(entry.client())
        .param(entry.correlationId())
        .param(prevHash)
        .param(entryHash)
        .update();
  }

  /**
   * The canonical text an entry is hashed over.
   *
   * <p>Every field that carries meaning, newline-separated, predecessor first. A verification run
   * rebuilds this string from the stored row and must arrive at the stored hash, so the order and
   * the null treatment below are part of the format rather than of this method.
   *
   * @param prevHash the predecessor's hash
   * @param tenantId whose chain
   * @param seq the sequence number
   * @param occurredAt when it happened
   * @param entry the entry
   * @param diff the canonical JSON of the diff
   * @return the SHA-256 of the canonical form
   */
  static byte[] hash(
      byte[] prevHash, UUID tenantId, long seq, Instant occurredAt, NewEntry entry, String diff) {
    return hashOf(prevHash, tenantId, seq, occurredAt, entry, diff, addressHash(entry.ip()));
  }

  /**
   * The same hash, with the address's digest supplied rather than derived.
   *
   * <p>What a verification run needs: the stored row no longer holds the address once a retention
   * run has cleared it, and the hash was never over the address itself for exactly that reason.
   *
   * @param prevHash the predecessor's hash
   * @param tenantId whose chain
   * @param seq the sequence number
   * @param occurredAt when it happened
   * @param entry the entry, whose {@code ip} is ignored here
   * @param diff the canonical JSON of the diff
   * @param ipHash the digest of the address, or {@code null} when there was none
   * @return the SHA-256 of the canonical form
   */
  static byte[] hashOf(
      byte[] prevHash,
      UUID tenantId,
      long seq,
      Instant occurredAt,
      NewEntry entry,
      String diff,
      byte[] ipHash) {
    StringBuilder canonical = new StringBuilder(256);
    canonical
        .append(java.util.HexFormat.of().formatHex(prevHash))
        .append('\n')
        .append(tenantId)
        .append('\n')
        .append(seq)
        .append('\n')
        // ISO-8601 in UTC to nanosecond precision: the same instant must render
        // the same way on every machine that verifies it.
        .append(occurredAt)
        .append('\n')
        .append(entry.actorKind().name())
        .append('\n')
        .append(orEmpty(entry.actorId()))
        .append('\n')
        .append(orEmpty(entry.actorLabel()))
        .append('\n')
        .append(orEmpty(entry.action()))
        .append('\n')
        .append(orEmpty(entry.resourceType()))
        .append('\n')
        .append(orEmpty(entry.resourceId()))
        .append('\n')
        .append(diff)
        .append('\n')
        // THE ADDRESS AS A HASH, not as itself. REQ-PRIV-006 removes it from
        // ordinary entries after seven days, and a chain computed over a column
        // somebody is required to clear would break at every cleared row —
        // honouring one requirement would forge evidence against another. The
        // hash never changes, so the tamper evidence stays complete and the
        // address stays removable.
        .append(ipHash == null ? "" : java.util.HexFormat.of().formatHex(ipHash))
        .append('\n')
        .append(orEmpty(entry.client()))
        .append('\n')
        .append(orEmpty(entry.correlationId()));
    return sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
  }

  /**
   * What the first entry of a tenant chains from.
   *
   * <p>Derived from the tenant id rather than a constant, so two tenants' first entries do not
   * share a predecessor — and so an empty chain is distinguishable from one whose first entry was
   * removed.
   *
   * @param tenantId the tenant
   * @return the genesis hash
   */
  static byte[] genesisOf(UUID tenantId) {
    return sha256((GENESIS + tenantId).getBytes(StandardCharsets.UTF_8));
  }

  /**
   * The diff as JSON with its keys in a fixed order.
   *
   * <p>Sorted, because the hash is over the text: a map that serialised its keys in a different
   * order on a different day would produce a different hash for the same change, and the
   * verification run would report tampering.
   *
   * @param diff what changed, possibly {@code null}
   * @return the canonical JSON object
   */
  private String canonicalJson(Map<String, Object> diff) {
    return canonicalJson(json, diff);
  }

  /**
   * The canonical form of a diff, from the map a block passed.
   *
   * @param mapper the serialiser
   * @param diff what changed, possibly {@code null}
   * @return the canonical JSON object
   */
  static String canonicalJson(ObjectMapper mapper, Map<String, Object> diff) {
    if (diff == null || diff.isEmpty()) {
      return "{}";
    }
    return mapper.writeValueAsString(new TreeMap<>(diff));
  }

  /**
   * The canonical form of a diff, from what the column gives back.
   *
   * <p>PostgreSQL's {@code jsonb} keeps the value and not the text: it drops whitespace and orders
   * keys its own way, so the stored text is not the string that was hashed. Parsing it and
   * re-serialising through the same sorted form is what makes the two agree — one canonicaliser,
   * two inputs.
   *
   * @param mapper the serialiser
   * @param stored what {@code diff::text} returned
   * @return the canonical JSON object
   */
  @SuppressWarnings("unchecked")
  static String canonicalJsonOf(ObjectMapper mapper, String stored) {
    if (stored == null || stored.isBlank() || "{}".equals(stored)) {
      return "{}";
    }
    return canonicalJson(mapper, mapper.readValue(stored, Map.class));
  }

  /**
   * The SHA-256 of an address, or {@code null} when there is none.
   *
   * @param ip the address as it was seen, possibly {@code null}
   * @return the digest for the column, or {@code null}
   */
  static byte[] addressHash(String ip) {
    return ip == null || ip.isBlank() ? null : sha256(ip.getBytes(StandardCharsets.UTF_8));
  }

  private static String orEmpty(Object value) {
    return value == null ? "" : value.toString();
  }

  private static byte[] sha256(byte[] input) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(input);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is part of every JRE", impossible);
    }
  }

  /**
   * Reads one row.
   *
   * @param rs the result set
   * @param row the row number, unused
   * @return the view
   * @throws SQLException when a column cannot be read
   */
  private static AuditView toView(ResultSet rs, int row) throws SQLException {
    return new AuditView(
        rs.getLong("seq"),
        rs.getObject("occurred_at", java.time.OffsetDateTime.class).toInstant(),
        ActorKind.valueOf(rs.getString("actor_kind")),
        rs.getObject("actor_id", UUID.class),
        rs.getString("actor_label"),
        rs.getString("action"),
        rs.getString("resource_type"),
        rs.getObject("resource_id", UUID.class),
        rs.getString("diff"),
        rs.getString("ip"),
        rs.getString("client"),
        rs.getString("correlation_id"));
  }

  // --- the transaction-scoped tail -----------------------------------------

  private static String key(UUID tenantId) {
    return AuditLogAdapter.class.getName() + ":" + tenantId;
  }

  private static Tail boundTail(UUID tenantId) {
    return (Tail) TransactionSynchronizationManager.getResource(key(tenantId));
  }

  private static void bindTail(UUID tenantId, Tail tail) {
    String key = key(tenantId);
    if (TransactionSynchronizationManager.hasResource(key)) {
      TransactionSynchronizationManager.unbindResource(key);
    }
    TransactionSynchronizationManager.bindResource(key, tail);
  }

  /**
   * Where this tenant's chain has got to.
   *
   * @param seq the last sequence number, zero for an empty chain
   * @param hash the last entry's hash, or the genesis value
   */
  private record Tail(long seq, byte[] hash) {}
}
