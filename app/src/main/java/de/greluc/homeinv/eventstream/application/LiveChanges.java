/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.eventstream.application;

import de.greluc.homeinv.eventstream.api.LiveStreams;
import de.greluc.homeinv.platform.TenantScopedEvent;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Tells connected browsers that something changed, and nothing else (REQ-API-011).
 *
 * <h2>What travels, and what deliberately does not</h2>
 *
 * <p>A <b>kind</b> and a tenant. Not an id, not a name, not a field. An open view learns that
 * items changed and re-reads the list it is showing, through the ordinary API, which applies the
 * ordinary permissions.
 *
 * <p>Sending the id would be faster and would leak: a member whose role is scoped to one part of
 * the location tree would learn that an item they may not see exists and just changed. Within one
 * tenant that is a small thing, and it is the kind of small thing that is discovered later with an
 * exploit attached. The refetch costs one request on a change somebody in the same tenant made.
 *
 * <h2>Why it goes through Valkey rather than staying in the process</h2>
 *
 * <p>{@code api} runs {@code 1..n} replicas. A change committed on one of them has to reach a
 * browser connected to another, and an in-process listener would deliver to whichever replica
 * happened to serve the write — so the feature would work with one replica and stop working on the
 * day somebody scaled it, which is the worst possible failure shape. Valkey is in every profile
 * already ({@code deploy/services.yaml}), and what is published is a word and a UUID.
 *
 * <p>The message is <b>not</b> the durable event. Those go through the outbox to RabbitMQ
 * ([ADR-0051]) and are delivered at least once; this is a nudge, and a nudge that is lost costs a
 * view one late refresh rather than a fact.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LiveChanges implements LiveStreams {

  /**
   * The one Valkey channel. The tenant is inside the message, not in the channel name.
   *
   * <p>One channel and not one per tenant: a subscription per tenant would be a subscription
   * churning as tenants come and go, and the filtering a channel name would save is a string
   * comparison on a message that already arrived.
   */
  public static final String CHANNEL = "homeinv.live";

  /**
   * How many streams one replica keeps per tenant before it refuses more.
   *
   * <p>A browser opens one; a person with six tabs opens six. The bound exists because an
   * unbounded list of open responses is a memory leak somebody else controls.
   */
  private static final int STREAMS_PER_TENANT = 64;

  private final StringRedisTemplate valkey;
  private final Clock clock;

  /** The streams this replica is holding, by tenant. */
  private final Map<UUID, List<LiveStreams.Stream>> streams = new ConcurrentHashMap<>();

  /**
   * Publishes a nudge once the change is committed.
   *
   * <p>{@code AFTER_COMMIT}: a browser told to refresh before the transaction lands would read the
   * old state and stay stale until the next change, which is worse than not being told.
   *
   * @param event what happened, which is read for its tenant and its kind alone
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onCommitted(TenantScopedEvent event) {
    publish(event);
  }

  /**
   * Publishes a nudge for a change raised outside a transaction.
   *
   * <p>{@code @TransactionalEventListener} does nothing at all when there is no transaction, which
   * would silently drop every event a scheduled run or a worker raises on its own. This is the same
   * listener for that case, and the guard below is what stops one change from sending two nudges.
   *
   * @param event what happened
   */
  @EventListener
  public void onRaisedWithoutATransaction(TenantScopedEvent event) {
    if (!org.springframework.transaction.support.TransactionSynchronizationManager
        .isActualTransactionActive()) {
      publish(event);
    }
  }

  private void publish(TenantScopedEvent event) {
    String kind = kindOf(event);
    try {
      valkey.convertAndSend(CHANNEL, event.tenantId() + " " + kind);
    } catch (RuntimeException unreachable) {
      // A nudge is not a fact. Valkey being away costs an open view a late
      // refresh, and taking the write down with it would be the wrong trade by
      // a wide margin.
      log.debug("A live change could not be published", unreachable);
    }
  }

  /**
   * Hands a nudge to the streams this replica holds.
   *
   * @param tenantId whose
   * @param kind what changed
   */
  public void deliver(UUID tenantId, String kind) {
    List<LiveStreams.Stream> open = streams.get(tenantId);
    if (open == null) {
      return;
    }
    for (LiveStreams.Stream stream : open) {
      stream.send(kind, clock.instant().toString());
    }
  }

  /**
   * Nudges every open stream on this replica, whatever tenant it belongs to.
   *
   * <p>Local by design: a heartbeat exists to keep a connection from being closed for being
   * idle, and a connection is idle on the replica that holds it. Publishing one through
   * Valkey would be every replica telling every other replica to do what each of them can do
   * for itself.
   */
  public void heartbeat() {
    String at = clock.instant().toString();
    for (List<LiveStreams.Stream> open : streams.values()) {
      for (LiveStreams.Stream stream : open) {
        stream.send("heartbeat", at);
      }
    }
  }

  /**
   * Registers a stream for a tenant.
   *
   * @param tenantId whose
   * @param stream what to send to
   * @return {@code false} when this replica already holds as many as it will for that tenant
   */
  @Override
  public boolean register(UUID tenantId, LiveStreams.Stream stream) {
    List<LiveStreams.Stream> open = streams.computeIfAbsent(tenantId, id -> new CopyOnWriteArrayList<>());
    if (open.size() >= STREAMS_PER_TENANT) {
      return false;
    }
    open.add(stream);
    return true;
  }

  /**
   * Forgets a stream that has closed.
   *
   * @param tenantId whose
   * @param stream which
   */
  @Override
  public void forget(UUID tenantId, LiveStreams.Stream stream) {
    List<LiveStreams.Stream> open = streams.get(tenantId);
    if (open != null) {
      open.remove(stream);
    }
  }

  /**
   * How many streams this replica holds for a tenant.
   *
   * @param tenantId whose
   * @return the count, for the operator view and for tests
   */
  @Override
  public int openStreams(UUID tenantId) {
    return streams.getOrDefault(tenantId, List.of()).size();
  }

  /**
   * The kind an event belongs to, which is what a view listens for.
   *
   * <p>Derived from the event's own type rather than declared on it: a new event of a known kind
   * should not need a second decision, and an event of an unknown kind is still worth a nudge —
   * a view that refreshes once too often is right, and one that never hears is wrong.
   *
   * @param event the event
   * @return one word, lower case
   */
  static String kindOf(TenantScopedEvent event) {
    String name = event.getClass().getSimpleName();
    if (name.startsWith("Item")) {
      return "item";
    }
    if (name.startsWith("Location")) {
      return "location";
    }
    if (name.startsWith("Tag")) {
      return "tag";
    }
    if (name.startsWith("Type") || name.startsWith("Field")) {
      return "type";
    }
    return "change";
  }

}
