/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.application;

import de.greluc.homeinv.authorization.api.AccessControl;
import de.greluc.homeinv.inventory.api.BulkItemOperations;
import de.greluc.homeinv.platform.TenantContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * One change applied to many items, a transaction per entry (REQ-CORE-011, ADR-0063).
 *
 * <p>{@link BulkItemOperations} states the contract and why it is shaped this way.
 * {@link BulkEntryRunner} owns the transaction boundary, because a propagation needs a proxy. What
 * is left here is the loop, the checks that belong to the call rather than to an entry, and the
 * single permission decision.
 *
 * <h2>This method opens no transaction</h2>
 *
 * <p>Deliberately, and it is the reason there is no {@code @Transactional} on it. An enclosing
 * transaction would be suspended and resumed 500 times, hold a second connection for the whole
 * call, and commit nothing — and if anything here ever wrote, an entry's failure would be able to
 * reach it. The only work outside an entry is reading the caller's role, and
 * {@code RoleDefinitionAdapter} opens its own transaction for that.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DefaultBulkItemOperations implements BulkItemOperations {

  private final BulkEntryRunner runner;
  private final AccessControl access;

  /**
   * Applies the operation to every entry.
   *
   * @param command which operation, its target, and the items
   * @param actor the authenticated user
   * @return one outcome per entry, in the order the entries were given
   * @throws IllegalArgumentException when the command is unusable as a whole
   */
  @Override
  public BulkOutcome apply(BulkCommand command, UUID actor) {
    UUID tenantId = TenantContext.require();
    usable(command);
    // Once, before anything: a role that may not delete may not delete the first
    // item either, and telling it so 500 times in a body is not an answer.
    access.require(BulkEntryRunner.permissionFor(command.operation()));

    List<EntryOutcome> outcomes = new ArrayList<>(command.entries().size());
    for (Entry entry : command.entries()) {
      RuntimeException failure = null;
      try {
        runner.apply(command, entry, actor);
      } catch (RuntimeException caught) {
        // Every entry's failure is that entry's, including one nobody planned
        // for. An unexpected exception becomes a 500 in this entry's status line
        // rather than in the whole response -- but it is logged at error, because
        // a bug that only ever appears as one line among five hundred is a bug
        // nobody reads.
        failure = caught;
        log.atLevel(expected(caught) ? org.slf4j.event.Level.DEBUG : org.slf4j.event.Level.ERROR)
            .setCause(expected(caught) ? null : caught)
            .log(
                "Bulk {} failed for item {} in tenant {}: {}",
                command.operation(),
                entry.itemId(),
                tenantId,
                caught.toString());
      }
      outcomes.add(new EntryOutcome(entry.itemId(), failure));
    }

    BulkOutcome outcome = new BulkOutcome(outcomes);
    log.debug(
        "Bulk {} over {} item(s) in tenant {}: {} applied.",
        command.operation(),
        outcomes.size(),
        tenantId,
        outcomes.stream().filter(EntryOutcome::applied).count());
    return outcome;
  }

  // -------------------------------------------------------------------------

  /**
   * Refuses a command no amount of per-entry reporting could rescue.
   *
   * <p>These are faults of the request rather than of an item, so they fail the call instead of
   * producing 500 identical status lines. The cap is the one of 08 §8.2 and REQ-SEC-065: 500
   * entries are 500 transactions, and a request whose length the caller chooses is a request that
   * can hold a connection for as long as it likes.
   *
   * @param command what was asked for
   * @throws IllegalArgumentException when there are no entries, too many, a repeated item, or the
   *     operation's target is missing
   */
  private void usable(BulkCommand command) {
    if (command.entries().isEmpty()) {
      throw new IllegalArgumentException("A bulk operation needs at least one entry");
    }
    if (command.entries().size() > MAX_ENTRIES) {
      throw new IllegalArgumentException(
          "A bulk operation takes at most " + MAX_ENTRIES + " entries");
    }
    Set<UUID> seen = new HashSet<>();
    for (Entry entry : command.entries()) {
      if (!seen.add(entry.itemId())) {
        // Two entries for one item would each get a status line, and the second
        // would report on a state the first had already changed. There is no
        // honest answer to give, so the request is refused instead.
        throw new IllegalArgumentException("An item appears twice: " + entry.itemId());
      }
    }
    switch (command.operation()) {
      case MOVE -> required(command.locationId(), "locationId", "move");
      case TAG -> required(command.tagId(), "tagId", "tag");
      case CHANGE_TYPE -> required(command.itemTypeId(), "itemTypeId", "change of type");
      case DELETE -> {
        // Takes no target: what is deleted is what the entries name.
      }
    }
  }

  /**
   * Refuses an operation whose target was left out.
   *
   * @param value what was sent
   * @param field what it is called on the wire, so the message points at the request
   * @param operation what needed it, in words
   * @throws IllegalArgumentException when the value is missing
   */
  private static void required(UUID value, String field, String operation) {
    if (value == null) {
      throw new IllegalArgumentException("A bulk " + operation + " needs a " + field);
    }
  }

  /**
   * Whether this failure is one an entry is expected to have.
   *
   * <p>An item somebody else deleted, a version that has moved on, attributes the new type refuses
   * — these are answers, not faults, and logging each at error would make a busy log out of a
   * working feature. Anything else is a bug and is logged as one.
   *
   * @param failure what the entry raised
   * @return true when the exception is part of the contract
   */
  private static boolean expected(RuntimeException failure) {
    return failure instanceof de.greluc.homeinv.platform.NotFoundException
        || failure instanceof de.greluc.homeinv.platform.StaleVersionException
        || failure instanceof de.greluc.homeinv.authorization.api.AccessDeniedException
        || failure instanceof de.greluc.homeinv.catalog.api.InvalidAttributesException
        || failure instanceof de.greluc.homeinv.idempotency.api.IdempotencyKeyConflictException
        || failure instanceof IllegalArgumentException;
  }
}
