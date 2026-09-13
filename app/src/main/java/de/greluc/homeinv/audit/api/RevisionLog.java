/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.audit.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The revision history of REQ-CORE-010: what a thing looked like after every change.
 *
 * <h2>Why the blocks that change things do not keep their own</h2>
 *
 * <p>{@code inventory} and {@code locations} both need one, and a copy in each schema would be two
 * mechanisms to get right, two retention runs and two answers to "who changed this". The history is
 * one thing, it lives in {@code audit} where 07 §7.8 puts it, and the blocks write to it through
 * this port.
 *
 * <h2>Snapshots, not diffs</h2>
 *
 * <p>Each revision carries the whole state it produced. A diff would be smaller and would make both
 * operations that matter — reading an old state and restoring one — a replay of everything since,
 * which a single gap makes impossible. An item is a few hundred bytes.
 *
 * <h2>What this is not</h2>
 *
 * <p>Not the audit log. That one is append-only with a hash chain and records <em>that</em> a thing
 * happened, for a reader who must be able to prove nothing was altered afterwards; this records
 * <em>what the thing looked like</em>, so a person can put it back. They answer different questions
 * and are kept apart on purpose.
 */
public interface RevisionLog {

  /**
   * Records the state a change produced.
   *
   * <p>Runs in the caller's transaction, so a change and its revision are one commit: a history with
   * a gap where a write succeeded is worse than no history, because it reads as if nothing happened.
   *
   * <p>The revision number is assigned here, from what this entity's history already holds. It is
   * deliberately not the entity's optimistic-lock version: a final removal changes nothing about the
   * row and would therefore record the number the deletion before it already used.
   *
   * @param entityType what kind of thing
   * @param entityId which one
   * @param kind what happened to it
   * @param snapshot the state after the change, as JSON text
   * @param actor who made the change
   * @return the number this revision was given, counting from one
   */
  long record(EntityType entityType, UUID entityId, ChangeKind kind, String snapshot, UUID actor);

  /**
   * One page of a thing's history, newest first.
   *
   * @param entityType what kind of thing
   * @param entityId which one
   * @param cursor an opaque cursor from a previous page, or {@code null} for the first
   * @param limit how many at most; capped at 200
   * @return the page and a cursor for the next one
   */
  RevisionPage history(EntityType entityType, UUID entityId, String cursor, int limit);

  /**
   * One revision of one thing.
   *
   * @param entityType what kind of thing
   * @param entityId which one
   * @param revision which revision
   * @return the revision
   * @throws de.greluc.homeinv.platform.NotFoundException when the tenant has no such revision
   */
  RevisionView revision(EntityType entityType, UUID entityId, long revision);

  /** What a revision is of. */
  enum EntityType {
    /** An item. */
    ITEM,
    /** A place in the location tree. */
    LOCATION
  }

  /** What happened to produce a revision. */
  enum ChangeKind {
    /** The thing came into existence. */
    CREATED,
    /** Something about it changed. */
    UPDATED,
    /** It went into the trash, recoverably (REQ-CORE-009). */
    TRASHED,
    /** It came back out of the trash. */
    RESTORED,
    /**
     * It was finally removed.
     *
     * <p>The revision stays. That is the one place this history outlives what it describes, and it
     * is why {@code revision_record} carries no foreign key to the row: a removal that erased its
     * own record would leave nothing to say the thing ever existed.
     */
    PURGED
  }

  /**
   * One entry of a history.
   *
   * @param revision this entry's number in the entity's history, counting from one
   * @param kind what happened
   * @param snapshot the state it produced, as JSON text
   * @param changedAt when
   * @param changedBy who, or {@code null} where a system process made the change
   */
  record RevisionView(long revision, ChangeKind kind, String snapshot, Instant changedAt, UUID changedBy) {}

  /**
   * One page of history.
   *
   * @param items the revisions on this page, newest first
   * @param nextCursor the cursor for the next page, or {@code null} when this was the last
   */
  record RevisionPage(List<RevisionView> items, String nextCursor) {}
}
