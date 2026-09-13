/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.api;

import de.greluc.homeinv.idempotency.api.RequestKey;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * One change applied to many items at once (REQ-CORE-011).
 *
 * <h2>One operation over a selection, not a batch of unrelated calls</h2>
 *
 * <p>A caller picks items in a list and presses <em>move</em>, <em>assign tag</em>,
 * <em>change type</em> or <em>delete</em> — that is what the bulk bar of the design system offers
 * and what this interface takes. The operation and its target are stated once; the entries name
 * only the items. A heterogeneous batch — move this, delete that — would be a different feature
 * with a different name, and nothing in the product asks for one.
 *
 * <h2>Partial success, and what that costs</h2>
 *
 * <p>Every entry gets an outcome of its own, and one that fails does not take the others with it
 * (08 §8.2). That is not the usual all-or-nothing of a transaction, and it is deliberate: a
 * selection of 500 rows made in a list is a selection a person did not inspect item by item, so one
 * item that has since been deleted by somebody else must not undo 499 moves.
 *
 * <p>Each entry is therefore <b>its own transaction</b> (ADR-0063). A savepoint per entry inside one
 * transaction would have been the cheaper shape and JPA cannot give it: Spring installs no savepoint
 * manager for Hibernate at all, and even with one, an operation that throws while participating in
 * an enclosing transaction calls {@code EntityTransaction.setRollbackOnly()}, which JPA makes
 * irreversible. A failure that reaches the database poisons the transaction regardless. Partial
 * success needs a rollback boundary that is a real transaction.
 *
 * <h2>Idempotency is per entry</h2>
 *
 * <p>Each entry may carry its own {@code Idempotency-Key} (08 §8.2). A client whose connection
 * dropped halfway resends the whole selection; the entries that were applied are recognised by
 * their keys and do nothing a second time, and the rest are applied. A single key for the whole
 * call could not express that, because the call is only ever partly done.
 */
public interface BulkItemOperations {

  /**
   * How many entries one call may carry (08 §8.2, REQ-SEC-065).
   *
   * <p>A limit and not a guideline: 500 entries are 500 transactions, each taking a connection from
   * the pool in turn, and a request whose length the caller chooses is a request that can hold the
   * pool for as long as it likes.
   */
  int MAX_ENTRIES = 500;

  /**
   * Applies the operation to every entry.
   *
   * <p>The caller's role is checked once, for the operation — moving needs
   * {@code inventory:item:update}, tagging {@code tagging:tag:assign}, deleting
   * {@code inventory:item:delete}. What is checked per entry is everything about the item itself:
   * that it exists in this tenant, that it is within the caller's location scope, that its version
   * is the one the caller acted on.
   *
   * @param command which operation, its target, and the items
   * @param actor the authenticated user, recorded in the audit columns of every item touched
   * @return one outcome per entry, in the order the entries were given
   * @throws de.greluc.homeinv.authorization.api.AccessDeniedException when the caller's role does
   *     not hold the permission the operation needs. Raised before any entry is attempted, because
   *     a role that may not move anything may not move the first item either
   * @throws IllegalArgumentException when the command is not usable at all — no entries, more than
   *     {@link #MAX_ENTRIES}, the same item twice, or the target the operation needs left out
   */
  BulkOutcome apply(BulkCommand command, UUID actor);

  /**
   * What a bulk call does to each of its items.
   *
   * <p>The four of REQ-CORE-011 and no more. Each names a target it needs, except {@link #DELETE},
   * which needs none.
   */
  enum Operation {
    /** Puts every item in one location. Refused for a digital item, which has none. */
    MOVE,
    /** Assigns one tag to every item. An item that already carries it is unchanged. */
    TAG,
    /**
     * Writes every item against the published version of one type.
     *
     * <p>Attributes whose key the new type declares with the same data type are carried over; the
     * rest are dropped, and the revision written by the change is what still holds them (decided
     * with the owner, 2026-09-13). A value the new type refuses — a number outside its range, a
     * value list that no longer has that entry — fails that entry rather than being dropped
     * silently, because dropping it would lose data the caller could have corrected.
     */
    CHANGE_TYPE,
    /** Moves every item to the trash, exactly as deleting one does — recoverable, not a purge. */
    DELETE
  }

  /**
   * What to do, to what, to which items.
   *
   * @param operation which of the four
   * @param locationId where {@link Operation#MOVE} puts them; ignored otherwise
   * @param tagId what {@link Operation#TAG} assigns; ignored otherwise
   * @param itemTypeId the type {@link Operation#CHANGE_TYPE} writes them against — the type and not
   *     one of its versions, so the version published today is the server's to resolve
   *     (REQ-CORE-025); ignored otherwise
   * @param entries the items, at most {@link #MAX_ENTRIES} of them, each named once
   */
  record BulkCommand(
      Operation operation, UUID locationId, UUID tagId, UUID itemTypeId, List<Entry> entries) {

    /**
     * Rejects a command that names no operation and copies the entries.
     *
     * @throws NullPointerException when the operation or the entry list is missing
     */
    public BulkCommand {
      Objects.requireNonNull(operation, "A bulk command states its operation");
      entries = List.copyOf(Objects.requireNonNull(entries, "A bulk command states its entries"));
    }
  }

  /**
   * One item in a bulk call.
   *
   * @param itemId the item
   * @param expectedVersion the version the caller acted on, from the {@code ETag} of its last read;
   *     empty skips the check for this entry. Per entry and not per call, because there is no
   *     {@code If-Match} header that could carry 500 versions (REQ-API-004)
   * @param idempotency this entry's own {@code Idempotency-Key} and the hash of what it asks for,
   *     or empty when the client sent none
   */
  record Entry(UUID itemId, OptionalLong expectedVersion, Optional<RequestKey> idempotency) {

    /**
     * Rejects an entry that names no item and no absent-value placeholder.
     *
     * @throws NullPointerException when any component is missing
     */
    public Entry {
      Objects.requireNonNull(itemId, "An entry names an item");
      Objects.requireNonNull(expectedVersion, "Use OptionalLong.empty(), not null");
      Objects.requireNonNull(idempotency, "Use Optional.empty(), not null");
    }
  }

  /**
   * What became of every entry.
   *
   * @param entries one outcome per entry, in the order the entries were given
   */
  record BulkOutcome(List<EntryOutcome> entries) {

    /**
     * Copies the outcomes.
     *
     * @throws NullPointerException when the list is missing
     */
    public BulkOutcome {
      entries = List.copyOf(Objects.requireNonNull(entries, "An outcome lists its entries"));
    }

    /**
     * Whether every entry was applied.
     *
     * <p>What decides the status code of the whole call: {@code 200} when this is true, {@code 207}
     * when it is not (08 §8.2, decided with the owner 2026-09-13). The body says the same thing
     * either way — the code exists so a client can tell without reading it.
     *
     * @return true when no entry failed
     */
    public boolean complete() {
      return entries.stream().allMatch(EntryOutcome::applied);
    }
  }

  /**
   * What became of one entry.
   *
   * <p>The failure is the exception itself rather than a code of this block's own invention. The
   * access layer already turns every one of these into an RFC 9457 problem with a registered
   * {@code type}, and a second vocabulary here would be a second list to keep in step with
   * {@code docs/reference/problem-types.yaml}.
   *
   * @param itemId which item
   * @param failure why it did not happen, or {@code null} when it did
   */
  record EntryOutcome(UUID itemId, RuntimeException failure) {

    /**
     * Rejects an outcome that names no item.
     *
     * @throws NullPointerException when the item is missing
     */
    public EntryOutcome {
      Objects.requireNonNull(itemId, "An outcome names its item");
    }

    /**
     * Whether this entry was applied.
     *
     * <p>An entry recognised by its {@code Idempotency-Key} as one already applied counts as
     * applied: that is what the key means, and a client retrying must see what it would have seen
     * the first time (REQ-API-005).
     *
     * @return true when nothing went wrong
     */
    public boolean applied() {
      return failure == null;
    }
  }
}
