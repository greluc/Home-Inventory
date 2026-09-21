/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.notification.infrastructure;

import de.greluc.homeinv.notification.api.Notifications;
import de.greluc.homeinv.notification.api.WebhookTargets.WebhookTargetView;
import de.greluc.homeinv.platform.EventType;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Webhook targets, in SQL (REQ-API-010).
 *
 * <p>Separate from {@link NotificationQueries} because the two have different natures and the same
 * table only in part: a target is edited and carries a version, a delivery is written once and then
 * only advanced by the delivery run. What they do share is the one statement that matters here —
 * {@link #queueDelivery} writes an ordinary {@code notification.notification} row, so the retries,
 * the attempt log and the dead letter are V51's and not a second copy of them.
 *
 * <p><b>The signing secret never leaves this class in the clear on a read path.</b> {@link
 * #targets} and {@link #target} do not select the column at all — a view that has no field for it
 * cannot leak it, and a query that does not read it cannot put it in a log.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookTargetQueries {

  /** The columns a target view needs, written out and without the secret (REQ-SEC-031). */
  private static final String TARGET_COLUMNS =
      "select id, url, description, event_types, enabled, version, created_at, updated_at"
          + " from notification.webhook_target";

  private final JdbcClient jdbc;

  /**
   * The tenant's targets, oldest first.
   *
   * @param tenantId whose
   * @return the targets
   */
  @Transactional(readOnly = true)
  public List<WebhookTargetView> targets(UUID tenantId) {
    return jdbc
        .sql(TARGET_COLUMNS + " where tenant_id = ? order by created_at, id")
        .param(tenantId)
        .query(WebhookTargetQueries::toView)
        .list();
  }

  /**
   * One target of this tenant.
   *
   * @param tenantId whose
   * @param id which one
   * @return it, or empty when this tenant has no such target — which is also the answer for another
   *     tenant's, because the policy is what filters and not this predicate
   */
  @Transactional(readOnly = true)
  public Optional<WebhookTargetView> target(UUID tenantId, UUID id) {
    return jdbc
        .sql(TARGET_COLUMNS + " where tenant_id = ? and id = ?")
        .param(tenantId)
        .param(id)
        .query(WebhookTargetQueries::toView)
        .optional();
  }

  /**
   * Whether another target of this tenant is already at this URL.
   *
   * <p>Asked before the write so that the answer is a named conflict rather than a constraint
   * violation arriving at the surface as a 500 — the treatment {@code SavedSearchQueries} gives a
   * taken name.
   *
   * @param tenantId whose
   * @param url the address
   * @param exceptId a target to ignore, for an update that keeps its own URL, or {@code null}
   * @return whether one exists
   */
  @Transactional(readOnly = true)
  public boolean urlTaken(UUID tenantId, String url, UUID exceptId) {
    return jdbc
            .sql(
                """
                select count(*) from notification.webhook_target
                where tenant_id = ? and url = ? and (?::uuid is null or id <> ?::uuid)
                """)
            .param(tenantId)
            .param(url)
            .param(exceptId)
            .param(exceptId)
            .query(Long.class)
            .single()
        > 0;
  }

  /**
   * Writes a new target.
   *
   * @param tenantId whose
   * @param id the id the caller generated, because the secret was sealed against it
   * @param url where deliveries go
   * @param description what a person calls it, or {@code null}
   * @param eventTypes which events it wants
   * @param sealedSecret the signing secret, already sealed
   * @param enabled whether it receives anything
   * @param actor who is creating it
   */
  @Transactional
  public void insert(
      UUID tenantId,
      UUID id,
      String url,
      String description,
      Set<EventType> eventTypes,
      String sealedSecret,
      boolean enabled,
      UUID actor) {
    jdbc.sql(
            """
            insert into notification.webhook_target
                (id, tenant_id, url, description, event_types, signing_secret, enabled,
                 created_by, updated_by)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)
        .param(id)
        .param(tenantId)
        .param(url)
        .param(description)
        .param(ids(eventTypes))
        .param(sealedSecret)
        .param(enabled)
        .param(actor)
        .param(actor)
        .update();
  }

  /**
   * Replaces a target.
   *
   * <p>The secret is updated only when one was supplied: a form that cannot show the stored secret
   * must not clear it by being saved.
   *
   * @param tenantId whose
   * @param id which one
   * @param url where deliveries go
   * @param description what a person calls it, or {@code null}
   * @param eventTypes which events it wants
   * @param sealedSecret the new sealed secret, or {@code null} to keep the stored one
   * @param enabled whether it receives anything
   * @param actor who is changing it
   */
  @Transactional
  public void update(
      UUID tenantId,
      UUID id,
      String url,
      String description,
      Set<EventType> eventTypes,
      String sealedSecret,
      boolean enabled,
      UUID actor) {
    jdbc.sql(
            """
            update notification.webhook_target
            set url = ?, description = ?, event_types = ?,
                -- The cast is not decoration: a bare parameter inside coalesce
                -- can leave the driver with no type to send a null as.
                signing_secret = coalesce(?::text, signing_secret),
                enabled = ?, version = version + 1, updated_at = now(), updated_by = ?
            where tenant_id = ? and id = ?
            """)
        .param(url)
        .param(description)
        .param(ids(eventTypes))
        .param(sealedSecret)
        .param(enabled)
        .param(actor)
        .param(tenantId)
        .param(id)
        .update();
  }

  /**
   * Removes a target, its deliveries and their attempts.
   *
   * @param tenantId whose
   * @param id which one
   * @return how many rows went, which is one or none
   */
  @Transactional
  public int remove(UUID tenantId, UUID id) {
    return jdbc
        .sql("delete from notification.webhook_target where tenant_id = ? and id = ?")
        .param(tenantId)
        .param(id)
        .update();
  }

  /**
   * The sealed signing secret of one target.
   *
   * <p>Its own method, called only by the delivery run and only for the target it is delivering to.
   * Reading a secret is not part of reading a target, and keeping the two apart is what makes the
   * one call site easy to find.
   *
   * @param tenantId whose
   * @param id which target
   * @return the sealed value, or empty when the target is gone — which happens when it was removed
   *     between the delivery being queued and being attempted
   */
  @Transactional(readOnly = true)
  public Optional<String> sealedSecret(UUID tenantId, UUID id) {
    return jdbc
        .sql("select signing_secret from notification.webhook_target where tenant_id = ? and id = ?")
        .param(tenantId)
        .param(id)
        .query(String.class)
        .optional();
  }

  /**
   * Which enabled targets of a tenant asked for this event type.
   *
   * <p>Containment against the GIN index of V73, so a tenant with many targets costs an index
   * lookup rather than a scan. Disabled targets are excluded here rather than later: a paused
   * receiver should not collect a dead-letter pile while somebody repairs it.
   *
   * @param tenantId whose
   * @param eventType what happened
   * @return the targets that want it, with their URLs
   */
  @Transactional(readOnly = true)
  public List<Subscriber> subscribersTo(UUID tenantId, EventType eventType) {
    return jdbc
        .sql(
            """
            select id, url
            from notification.webhook_target
            where tenant_id = ? and enabled and event_types @> array[?]::text[]
            order by created_at, id
            """)
        .param(tenantId)
        .param(eventType.id())
        .query((ResultSet rs, int row) -> new Subscriber(rs.getObject("id", UUID.class), rs.getString("url")))
        .list();
  }

  /**
   * Queues one delivery for one target.
   *
   * <p>An ordinary notification row with {@code webhook_target_id} instead of {@code user_id}, due
   * immediately. Everything after this — the attempt, the widening retry, the dead letter — is the
   * machinery V51 already has.
   *
   * @param tenantId whose
   * @param targetId where it goes
   * @param url the target's URL, copied onto the row so that a delivery keeps going where it was
   *     addressed even if the target is re-pointed while it is queued
   * @param eventType what happened, which is the row's kind and its subject line
   * @param body the document the receiver gets
   * @param idempotencyKey what the receiver and the channel both deduplicate on
   */
  @Transactional
  public void queueDelivery(
      UUID tenantId,
      UUID targetId,
      String url,
      EventType eventType,
      String body,
      String idempotencyKey) {
    jdbc.sql(
            """
            insert into notification.notification
                (tenant_id, webhook_target_id, kind, channel_key, address, subject, body_text,
                 language, idempotency_key, next_attempt_at)
            values (?, ?, ?, 'webhook', ?, ?, ?, 'en', ?, now())
            on conflict (tenant_id, idempotency_key) do nothing
            """)
        .param(tenantId)
        .param(targetId)
        .param(eventType.id())
        .param(url)
        .param(eventType.id())
        .param(body)
        .param(idempotencyKey)
        .update();
  }

  /**
   * What was delivered to one target, newest first.
   *
   * @param tenantId whose
   * @param targetId which target
   * @param limit how many at most
   * @return the deliveries
   */
  @Transactional(readOnly = true)
  public List<Notifications.QueuedNotification> deliveries(UUID tenantId, UUID targetId, int limit) {
    return jdbc
        .sql(
            """
            select id, channel_key, address, state, attempts, next_attempt_at
            from notification.notification
            where tenant_id = ? and webhook_target_id = ?
            order by created_at desc, id desc
            limit ?
            """)
        .param(tenantId)
        .param(targetId)
        .param(limit)
        .query(
            (ResultSet rs, int row) ->
                new Notifications.QueuedNotification(
                    rs.getObject("id", UUID.class),
                    rs.getString("channel_key"),
                    rs.getString("address"),
                    rs.getString("state"),
                    rs.getInt("attempts"),
                    instant(rs, "next_attempt_at")))
        .list();
  }

  /**
   * A target that wants an event, as the fan-out needs it.
   *
   * @param id the target
   * @param url where its deliveries go
   */
  public record Subscriber(UUID id, String url) {}

  private static String[] ids(Set<EventType> eventTypes) {
    return eventTypes.stream().map(EventType::id).toArray(String[]::new);
  }

  private static WebhookTargetView toView(ResultSet rs, int row) throws SQLException {
    return new WebhookTargetView(
        rs.getObject("id", UUID.class),
        rs.getString("url"),
        rs.getString("description"),
        eventTypes(rs.getArray("event_types")),
        rs.getBoolean("enabled"),
        rs.getLong("version"),
        instant(rs, "created_at"),
        instant(rs, "updated_at"));
  }

  /**
   * The stored names as types, skipping any this deployment no longer raises.
   *
   * <p>Skipping rather than failing: a name is removed from {@link EventType} by a release, and the
   * rows naming it are a tenant's configuration that nobody has had a chance to correct. Refusing
   * to read the target would hide every other event type on it too, which is a worse answer to
   * "somebody deleted a feature" than quietly dropping the one that is gone. It is logged, because
   * it should not happen twice.
   */
  private static Set<EventType> eventTypes(Array stored) throws SQLException {
    if (stored == null) {
      return Set.of();
    }
    String[] names = (String[]) stored.getArray();
    Set<EventType> types = new LinkedHashSet<>();
    List<String> unknown = new ArrayList<>();
    for (String name : names) {
      EventType.of(name).ifPresentOrElse(types::add, () -> unknown.add(name));
    }
    if (!unknown.isEmpty()) {
      log.warn(
          "A webhook target names {} event type(s) this deployment does not raise: {}."
              + " They are ignored; the target keeps the rest.",
          unknown.size(),
          unknown);
    }
    return types;
  }

  private static java.time.Instant instant(ResultSet rs, String column) throws SQLException {
    java.sql.Timestamp at = rs.getTimestamp(column);
    return at == null ? null : at.toInstant();
  }
}
