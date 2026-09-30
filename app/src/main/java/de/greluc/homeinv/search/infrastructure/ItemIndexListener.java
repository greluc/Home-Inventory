/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.search.infrastructure;

import de.greluc.homeinv.inventory.api.ItemCreated;
import de.greluc.homeinv.inventory.api.ItemDeleted;
import de.greluc.homeinv.inventory.api.ItemMoved;
import de.greluc.homeinv.inventory.api.ItemPurged;
import de.greluc.homeinv.inventory.api.ItemRestored;
import de.greluc.homeinv.inventory.api.ItemTypeChanged;
import de.greluc.homeinv.inventory.api.ItemUpdated;
import de.greluc.homeinv.search.application.SearchIndexer;
import de.greluc.homeinv.tagging.api.TagAssigned;
import de.greluc.homeinv.tagging.api.TagService;
import de.greluc.homeinv.tagging.api.TagUnassigned;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Turns what happened to an item into what the index holds (REQ-SRCH-005).
 *
 * <h2>Off the request thread</h2>
 *
 * <p>A write must not wait for an index. ADR-0008 sets the index lag at p95 under two seconds and
 * accepts that it is a lag: immediately after a write the detail view reads PostgreSQL anyway, so a
 * person always sees their own change at once. What the broker buys in exchange is the failure mode
 * 13 §13.6 describes — RabbitMQ down means the outbox backs up and the index goes stale
 * ({@code index-stale}), rather than the inventory refusing writes because a derived store is
 * unavailable (CLAUDE.md rule 10).
 *
 * <h2>One queue per event</h2>
 *
 * <p>Every method here ends in the same call, because the indexer reads the item again rather than
 * patching a document from an event's fields — so one queue for all seven looks like the tidier
 * shape. It is not: several listener methods on one queue are several <i>competing consumers</i> of
 * it, each taking whatever message is next whether or not it can read it, and the six that cannot
 * fail to convert. A queue per routing key is what makes a delivery's payload type the payload type
 * of the method that gets it.
 *
 * <p>Durable and named, so an instance that was down finds the work that arrived meanwhile. An
 * anonymous exclusive queue would lose every message published during a deployment.
 *
 * <h2>Only in the worker</h2>
 *
 * <p>{@code @Profile("worker")}, like every other consumer here: {@code api} and {@code worker} run
 * the same image and the same beans, and the profile is what decides which of them does the work
 * off the request thread (04 §4.1, ADR-0051). Without it this class exists in {@code api} too, and
 * two roles compete for each queue — which is not wrong, because the work is idempotent, but it
 * puts indexing back on the machine that is serving requests.
 */
@Slf4j
@Component
@Profile("worker")
@RequiredArgsConstructor
public class ItemIndexListener {

  private final SearchIndexer indexer;

  /**
   * Indexes an item that was created.
   *
   * @param event what happened
   */
  @RabbitListener(bindings = @QueueBinding(
      value = @Queue(name = QUEUE_PREFIX + "item-created.v1", durable = "true"),
      exchange = @Exchange(name = EXCHANGE, type = "topic", durable = "true"),
      key = "item-created.v1"))
  public void onCreated(ItemCreated event) {
    indexer.reindex(event.tenantId(), event.itemId());
  }

  /**
   * Re-indexes an item that was edited.
   *
   * @param event what happened
   */
  @RabbitListener(bindings = @QueueBinding(
      value = @Queue(name = QUEUE_PREFIX + "item-updated.v1", durable = "true"),
      exchange = @Exchange(name = EXCHANGE, type = "topic", durable = "true"),
      key = "item-updated.v1"))
  public void onUpdated(ItemUpdated event) {
    indexer.reindex(event.tenantId(), event.itemId());
  }

  /**
   * Re-indexes an item that was put somewhere else, which changes its location path.
   *
   * @param event what happened
   */
  @RabbitListener(bindings = @QueueBinding(
      value = @Queue(name = QUEUE_PREFIX + "item-moved.v1", durable = "true"),
      exchange = @Exchange(name = EXCHANGE, type = "topic", durable = "true"),
      key = "item-moved.v1"))
  public void onMoved(ItemMoved event) {
    indexer.reindex(event.tenantId(), event.itemId());
  }

  /**
   * Re-indexes an item written against another type, which rebuilds its attribute set.
   *
   * @param event what happened
   */
  @RabbitListener(bindings = @QueueBinding(
      value = @Queue(name = QUEUE_PREFIX + "item-type-changed.v1", durable = "true"),
      exchange = @Exchange(name = EXCHANGE, type = "topic", durable = "true"),
      key = "item-type-changed.v1"))
  public void onTypeChanged(ItemTypeChanged event) {
    indexer.reindex(event.tenantId(), event.itemId());
  }

  /**
   * Takes a trashed item out of the index at once.
   *
   * <p>Through {@code reindex} and not through a bare removal: the row is still there, and what
   * changed is the answer to "is it live". Letting the indexer discover that keeps one rule in one
   * place.
   *
   * @param event what happened
   */
  @RabbitListener(bindings = @QueueBinding(
      value = @Queue(name = QUEUE_PREFIX + "item-deleted.v1", durable = "true"),
      exchange = @Exchange(name = EXCHANGE, type = "topic", durable = "true"),
      key = "item-deleted.v1"))
  public void onDeleted(ItemDeleted event) {
    indexer.reindex(event.tenantId(), event.itemId());
  }

  /**
   * Puts a restored item back.
   *
   * @param event what happened
   */
  @RabbitListener(bindings = @QueueBinding(
      value = @Queue(name = QUEUE_PREFIX + "item-restored.v1", durable = "true"),
      exchange = @Exchange(name = EXCHANGE, type = "topic", durable = "true"),
      key = "item-restored.v1"))
  public void onRestored(ItemRestored event) {
    indexer.reindex(event.tenantId(), event.itemId());
  }

  /**
   * Forgets an item that is gone for good.
   *
   * <p>The one that does not read first: after a purge there is no row to read, and asking for one
   * would be a query whose answer is known.
   *
   * @param event what happened
   */
  @RabbitListener(bindings = @QueueBinding(
      value = @Queue(name = QUEUE_PREFIX + "item-purged.v1", durable = "true"),
      exchange = @Exchange(name = EXCHANGE, type = "topic", durable = "true"),
      key = "item-purged.v1"))
  public void onPurged(ItemPurged event) {
    indexer.forget(event.tenantId(), event.itemId());
  }

  /**
   * Re-indexes an item a tag was put on or taken off (REQ-SRCH-011).
   *
   * <p>A tag is part of what an item can be found by, so the document has to change when the
   * assignment does. Without this the tag reaches the index only on the item's next edit, which may
   * never come — and a person who tags something in order to find it later would not.
   *
   * @param event what happened
   */
  @RabbitListener(bindings = @QueueBinding(
      value = @Queue(name = QUEUE_PREFIX + "tag-assigned.v1", durable = "true"),
      exchange = @Exchange(name = TAGGING, type = "topic", durable = "true"),
      key = "tag-assigned.v1"))
  public void onTagAssigned(TagAssigned event) {
    onTagChanged(event.tenantId(), event.target(), event.targetId());
  }

  /**
   * Re-indexes an item a tag was taken off.
   *
   * @param event what happened
   */
  @RabbitListener(bindings = @QueueBinding(
      value = @Queue(name = QUEUE_PREFIX + "tag-unassigned.v1", durable = "true"),
      exchange = @Exchange(name = TAGGING, type = "topic", durable = "true"),
      key = "tag-unassigned.v1"))
  public void onTagUnassigned(TagUnassigned event) {
    onTagChanged(event.tenantId(), event.target(), event.targetId());
  }

  /**
   * Re-indexes the item behind a tag change, and ignores a tag on anything else.
   *
   * <p>A tag goes on an item or on a place. Only the first has a document; a tagged location
   * changes nothing about what any item is found by, because the document carries the path's ids
   * and not its tags.
   *
   * @param tenantId whose data changed
   * @param target what kind of thing was tagged
   * @param targetId which one
   */
  private void onTagChanged(java.util.UUID tenantId, TagService.TagTarget target, java.util.UUID targetId) {
    if (target == TagService.TagTarget.ITEM) {
      indexer.reindex(tenantId, targetId);
    }
  }

  /** The exchange {@code inventory}'s events are externalised onto. */
  private static final String EXCHANGE = "homeinv.inventory";

  /** The exchange {@code tagging}'s events are externalised onto. */
  private static final String TAGGING = "homeinv.tagging";

  /**
   * What each queue is called, plus the routing key it carries.
   *
   * <p>One queue per event and not one for all seven. Several listener methods on a single queue
   * are several <i>competing consumers</i> of it: whichever is free takes the next message whatever
   * it holds, and the six that cannot read it fail to convert. A queue per routing key is what
   * makes the payload type of a delivery the payload type of the method.
   */
  private static final String QUEUE_PREFIX = "homeinv.search.";
}
