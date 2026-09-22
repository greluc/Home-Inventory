/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.notification.application;

import de.greluc.homeinv.notification.infrastructure.WebhookTargetQueries;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.platform.TenantScopedEvent;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Turns a change into a delivery for every target that asked for it (REQ-API-010).
 *
 * <h2>Before the commit, in the same transaction</h2>
 *
 * <p>{@code BEFORE_COMMIT} and not {@code AFTER_COMMIT}, which is the opposite of what {@code
 * eventstream} does — and the difference is the whole point of the two. A live nudge that is lost
 * costs an open view one late refresh; a webhook that is lost is a fact a receiver never learns and
 * has no way to ask for. Writing the delivery row in the transaction that made the change is the
 * outbox pattern: either both land or neither does.
 *
 * <p>What that costs is stated rather than hidden: a failure here rolls back the change that
 * triggered it. The work is one indexed lookup and, in the ordinary case of a tenant with no
 * targets, no write at all — so the failure modes are the database being unavailable, which would
 * have rolled the change back anyway.
 *
 * <h2>What travels</h2>
 *
 * <p>The event type, the moment and the subject's id. Never the name, never a field, never the
 * event's own payload — a receiver re-reads through the ordinary API, which applies the ordinary
 * permissions (ADR-0078). It is also why this listener takes {@link TenantScopedEvent} and not the
 * concrete events: it has no use for anything they carry.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookFanOut {

  private final WebhookTargetQueries targets;
  private final Clock clock;

  /**
   * Queues the deliveries in the transaction that made the change.
   *
   * @param event what happened, read for its tenant, its type and its subject alone
   */
  @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
  public void onChanging(TenantScopedEvent event) {
    UUID ambient = TenantContext.current().orElse(null);
    if (!event.tenantId().equals(ambient)) {
      // The row would be refused by the policy anyway -- `SET LOCAL
      // app.tenant_id` was applied when this transaction began -- and being
      // refused here would roll back somebody's write over a bookkeeping
      // mismatch. Logged loudly instead: it means an event was raised for a
      // tenant other than the one whose transaction it is, which is a defect
      // rather than a state.
      log.error(
          "An event of type {} was raised for a tenant that is not the current one."
              + " No webhook delivery was queued for it.",
          event.eventType().id());
      return;
    }
    queue(event, event.tenantId());
  }

  /**
   * Queues the deliveries for a change raised outside a transaction.
   *
   * <p>{@code @TransactionalEventListener} does nothing at all when there is none, which would
   * silently drop every event a scheduled run raises on its own. Here the delivery gets a
   * transaction of its own — not atomic with anything, because there was nothing to be atomic with.
   *
   * @param event what happened
   */
  @EventListener
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void onRaisedWithoutATransaction(TenantScopedEvent event) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      return;
    }
    TenantContext.runAs(event.tenantId(), () -> queue(event, event.tenantId()));
  }

  private void queue(TenantScopedEvent event, UUID tenantId) {
    List<WebhookTargetQueries.Subscriber> subscribers =
        targets.subscribersTo(tenantId, event.eventType());
    if (subscribers.isEmpty()) {
      return;
    }
    String body = document(event);
    for (WebhookTargetQueries.Subscriber subscriber : subscribers) {
      targets.queueDelivery(
          tenantId,
          subscriber.id(),
          subscriber.url(),
          event.eventType(),
          body,
          UUID.randomUUID().toString());
    }
    log.debug(
        "{} webhook target(s) will be told about {}", subscribers.size(), event.eventType().id());
  }

  /**
   * The document a receiver gets, inside the plugin's signed envelope.
   *
   * <p>Written by hand rather than serialised, and that is safe here <b>because no free text
   * reaches it</b>: every value is an enum id, a UUID or an instant, and none of the three can
   * contain a quote or a backslash. The moment a name or a description is added to this document,
   * this becomes a serialiser call — see {@code plugins/webhook/src/payload.rs}, which builds its
   * envelope by hand for the opposite reason and escapes everything.
   *
   * @param event what happened
   * @return the JSON document
   */
  private String document(TenantScopedEvent event) {
    return "{\"type\":\""
        + event.eventType().id()
        + "\",\"at\":\""
        + clock.instant()
        + "\",\"id\":\""
        + event.subjectId()
        + "\"}";
  }
}
