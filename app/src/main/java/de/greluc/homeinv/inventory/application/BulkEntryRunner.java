/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.application;

import de.greluc.homeinv.idempotency.api.IdempotentRequests;
import de.greluc.homeinv.idempotency.api.RequestKey;
import de.greluc.homeinv.inventory.api.BulkItemOperations.BulkCommand;
import de.greluc.homeinv.inventory.api.BulkItemOperations.Entry;
import de.greluc.homeinv.inventory.api.BulkItemOperations.Operation;
import de.greluc.homeinv.inventory.api.ItemService;
import de.greluc.homeinv.tagging.api.TagService;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * One entry of a bulk call, in a transaction of its own (REQ-CORE-011, ADR-0063).
 *
 * <h2>Why this is a bean and not a method on the service</h2>
 *
 * <p>{@link Propagation#REQUIRES_NEW} only takes effect across a proxy. A private method on
 * {@link DefaultBulkItemOperations} calling itself would run in whatever transaction the caller
 * had, and catching the exception would not save the call: every operation here is itself
 * {@code @Transactional}, so one that throws while <em>participating</em> in an enclosing
 * transaction marks that transaction rollback-only. The loop would finish, report 499 successes,
 * and the commit would throw {@code UnexpectedRollbackException} — losing every one of them.
 * Partial success is not something a caught exception can produce.
 *
 * <h2>Why a transaction and not a savepoint</h2>
 *
 * <p>A savepoint per entry inside one transaction is the shape that keeps
 * <a href="../../../../../../../docs/adr/0031-audit-chain-per-tenant.md">ADR-0031</a>'s batching
 * rule literally true, and JPA cannot give it. Spring 7 installs a savepoint manager only when the
 * dialect's transaction data implements one, and neither {@code HibernateJpaDialect} nor the
 * default does — {@code NestedTransactionNotSupportedException}, every time. Supplying one would
 * not be enough either: the participating operation's rollback calls
 * {@code EntityTransaction.setRollbackOnly()}, which JPA makes irreversible, so the enclosing
 * commit fails anyway. ADR-0063 records the decision and amends ADR-0031's row.
 *
 * <h2>What a transaction per entry means here</h2>
 *
 * <p>An entry that fails leaves nothing behind, including the {@code Idempotency-Key} it would have
 * spent — so a client resending the whole selection retries exactly the entries that failed. It
 * also means the call is not atomic and was never meant to be: partial success is the requirement,
 * and a caller reads the body to learn which half happened.
 */
@Component
@Slf4j
@RequiredArgsConstructor
class BulkEntryRunner {

  private final ItemService items;
  private final TagService tags;
  private final IdempotentRequests requests;

  /**
   * What an entry's {@code Idempotency-Key} is spent on.
   *
   * <p>The operation is part of it, so a key spent moving an item cannot be accepted later as
   * having deleted one. {@code replay} compares the stored operation against this and refuses a
   * mismatch, which makes the difference a {@code 409} rather than a second, different change.
   */
  private static final String OPERATION_PREFIX = "POST /api/v1/items/bulk#";

  /** What an applied entry records under its key, for a reader of the table as much as a replay. */
  private static final String APPLIED = "{\"applied\":true}";

  /**
   * Applies the command to one item, in a savepoint of its own.
   *
   * <p>An entry carrying a key already spent on this same operation and item does nothing and
   * returns: that is what {@code Idempotency-Key} means, and the caller sees the outcome it would
   * have seen the first time (REQ-API-005).
   *
   * @param command the operation and its target, the same for every entry
   * @param entry this item, its expected version and its key
   * @param actor the authenticated user
   * @throws RuntimeException whatever the operation raises — this entry's transaction is rolled
   *     back and {@link DefaultBulkItemOperations} turns it into this entry's status line
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void apply(BulkCommand command, Entry entry, UUID actor) {
    String operation = OPERATION_PREFIX + command.operation().name().toLowerCase(Locale.ROOT);
    Optional<RequestKey> key = entry.idempotency();
    if (key.isPresent()
        && requests.replay(operation, key.get().key(), key.get().requestHash()).isPresent()) {
      log.debug("Bulk entry for item {} was already applied under its key.", entry.itemId());
      return;
    }

    switch (command.operation()) {
      case MOVE ->
          items.move(entry.itemId(), command.locationId(), entry.expectedVersion(), actor);
      // No version check: assigning a tag writes a row of its own and leaves the
      // item's own version alone, so there is nothing an If-Match could protect.
      // The single-item endpoint takes no If-Match either, for the same reason.
      //
      // An item that is not there is this entry's 404, raised by `tagging` through
      // the `TaggableTargets` port that `inventory` implements. This used to read
      // the item here first, because without that port the assignment reached the
      // database as a foreign-key violation.
      case TAG -> tags.assign(command.tagId(), TagService.TagTarget.ITEM, entry.itemId(), actor);
      case CHANGE_TYPE ->
          items.changeType(entry.itemId(), command.itemTypeId(), entry.expectedVersion(), actor);
      case DELETE -> items.delete(entry.itemId(), entry.expectedVersion(), actor);
    }

    key.ifPresent(
        spent -> requests.remember(operation, spent.key(), spent.requestHash(), APPLIED, actor));
  }

  /**
   * The permission the operation needs.
   *
   * <p>Here rather than in the endpoint because the endpoint cannot know it: one path serves four
   * operations, and {@code @RequiresPermission} is a constant. The coarse annotation on the handler
   * is {@code inventory:item:read} — what everybody reaching this path must hold — and this is the
   * decision that actually gates the change (ADR-0010, REQ-SEC-022).
   *
   * @param operation which of the four
   * @return the permission a caller must hold to run it over a selection
   */
  static de.greluc.homeinv.authorization.api.Permission permissionFor(Operation operation) {
    return switch (operation) {
      case MOVE, CHANGE_TYPE -> de.greluc.homeinv.authorization.api.Permission.ITEM_UPDATE;
      case TAG -> de.greluc.homeinv.authorization.api.Permission.TAG_ASSIGN;
      case DELETE -> de.greluc.homeinv.authorization.api.Permission.ITEM_DELETE;
    };
  }
}
