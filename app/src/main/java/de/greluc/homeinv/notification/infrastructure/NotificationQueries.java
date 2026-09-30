/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.notification.infrastructure;

import de.greluc.homeinv.notification.api.Notifications;
import de.greluc.homeinv.platform.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every statement the {@code notification} block runs.
 *
 * <p>Here and not in {@code application} because hand-written SQL belongs in infrastructure
 * (ADR-0017) — a rule ArchUnit enforces, and the reason the services above read like what they do
 * rather than like what they query.
 */
@Component
@RequiredArgsConstructor
public class NotificationQueries {

  private static final String QUEUED_COLUMNS =
      "select id, channel_key, address, state, attempts, next_attempt_at"
          + " from notification.notification";

  private static final String SUBSCRIPTION_COLUMNS =
      "select id, user_id, kind, channel_key, address, enabled from notification.subscription";

  private final JdbcClient jdbc;

  /**
   * Queues one notification, or leaves the one already there.
   *
   * @param tenantId whose
   * @param userId who to tell
   * @param kind what about
   * @param channelKey which channel
   * @param address where
   * @param subject the subject line
   * @param bodyText the plain text
   * @param bodyHtml the HTML, or {@code null}
   * @param language the recipient's language
   * @param idempotencyKey what makes queueing this twice queue it once
   * @return the queued notification, new or existing
   */
  @Transactional
  public Notifications.QueuedNotification queue(
      UUID tenantId,
      UUID userId,
      String kind,
      String channelKey,
      String address,
      String subject,
      String bodyText,
      String bodyHtml,
      String language,
      String idempotencyKey) {
    jdbc.sql(
            """
            insert into notification.notification
                (tenant_id, user_id, kind, channel_key, address, subject, body_text, body_html,
                 language, idempotency_key, next_attempt_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
            on conflict (tenant_id, idempotency_key) do nothing
            """)
        .param(tenantId)
        .param(userId)
        .param(kind)
        .param(channelKey)
        .param(address)
        .param(subject)
        .param(bodyText)
        .param(bodyHtml)
        .param(language)
        .param(idempotencyKey)
        .update();

    return jdbc
        .sql(QUEUED_COLUMNS + " where tenant_id = ? and idempotency_key = ?")
        .param(tenantId)
        .param(idempotencyKey)
        .query(NotificationQueries::toQueued)
        .single();
  }

  /**
   * One notification of the current tenant.
   *
   * @param notificationId which one
   * @return it, or empty
   */
  @Transactional(readOnly = true)
  public Optional<Notifications.QueuedNotification> notification(UUID notificationId) {
    return jdbc
        .sql(QUEUED_COLUMNS + " where tenant_id = ? and id = ?")
        .param(TenantContext.require())
        .param(notificationId)
        .query(NotificationQueries::toQueued)
        .optional();
  }

  /**
   * What is waiting and due for the current tenant.
   *
   * @param now the present
   * @param batch how many at most
   * @return the oldest first
   */
  @Transactional(readOnly = true)
  public List<Due> due(Instant now, int batch) {
    return jdbc
        .sql(
            """
            select id, channel_key, address, subject, body_text, body_html, language,
                   attempts, idempotency_key, webhook_target_id
            from notification.notification
            where tenant_id = ? and state = 'QUEUED' and next_attempt_at <= ?
            order by next_attempt_at
            limit ?
            """)
        .param(TenantContext.require())
        .param(java.sql.Timestamp.from(now))
        .param(batch)
        .query(
            (ResultSet rs, int row) ->
                new Due(
                    rs.getObject("id", UUID.class),
                    rs.getString("channel_key"),
                    rs.getString("address"),
                    rs.getString("subject"),
                    rs.getString("body_text"),
                    rs.getString("body_html"),
                    rs.getString("language"),
                    rs.getInt("attempts"),
                    rs.getString("idempotency_key"),
                    rs.getObject("webhook_target_id", UUID.class)))
        .list();
  }

  /**
   * Which tenants have something waiting.
   *
   * <p>Through the {@code SECURITY DEFINER} function of {@code V51}: the delivery run has no tenant
   * context — it is looking for the ones that need one — and the function returns ids and nothing
   * else.
   *
   * @param now the present
   * @return the tenants
   */
  @Transactional(readOnly = true)
  public List<UUID> tenantsWithSomethingDue(Instant now) {
    return jdbc
        .sql("select tenant_id from notification.tenants_with_due_notifications(?)")
        .param(java.sql.Timestamp.from(now))
        .query((ResultSet rs, int row) -> rs.getObject(1, UUID.class))
        .list();
  }

  /**
   * Records one attempt.
   *
   * @param tenantId whose
   * @param notificationId which notification
   * @param attemptNo which try
   * @param outcome what happened
   * @param detail what the far side said, bounded by the column
   */
  public void recordAttempt(
      UUID tenantId, UUID notificationId, int attemptNo, String outcome, String detail) {
    jdbc.sql(
            """
            insert into notification.delivery_attempt
                (tenant_id, notification_id, attempt_no, outcome, detail)
            values (?, ?, ?, ?, ?)
            on conflict (tenant_id, notification_id, attempt_no) do nothing
            """)
        .param(tenantId)
        .param(notificationId)
        .param(attemptNo)
        .param(outcome)
        .param(detail == null || detail.isBlank() ? null : detail.substring(0, Math.min(detail.length(), 1000)))
        .update();
  }

  /**
   * Marks a notification delivered.
   *
   * @param tenantId whose
   * @param notificationId which
   * @param attemptNo how many tries it took
   * @param providerMessageId what the channel called it, or {@code null}
   */
  public void markDelivered(
      UUID tenantId, UUID notificationId, int attemptNo, String providerMessageId) {
    jdbc.sql(
            """
            update notification.notification
            set state = 'DELIVERED', attempts = ?, next_attempt_at = null,
                delivered_at = now(), provider_message_id = ?
            where tenant_id = ? and id = ?
            """)
        .param(attemptNo)
        .param(providerMessageId == null || providerMessageId.isBlank() ? null : providerMessageId)
        .param(tenantId)
        .param(notificationId)
        .update();
  }

  /**
   * Puts a notification back in the queue for later.
   *
   * @param tenantId whose
   * @param notificationId which
   * @param attemptNo how many tries so far
   * @param nextAttemptAt when to try again
   */
  public void reschedule(
      UUID tenantId, UUID notificationId, int attemptNo, Instant nextAttemptAt) {
    jdbc.sql(
            """
            update notification.notification
            set attempts = ?, next_attempt_at = ?
            where tenant_id = ? and id = ?
            """)
        .param(attemptNo)
        .param(java.sql.Timestamp.from(nextAttemptAt))
        .param(tenantId)
        .param(notificationId)
        .update();
  }

  /**
   * Stops trying, and leaves everything readable.
   *
   * @param tenantId whose
   * @param notificationId which
   * @param attemptNo how many tries it took
   */
  public void deadLetter(UUID tenantId, UUID notificationId, int attemptNo) {
    jdbc.sql(
            """
            update notification.notification
            set state = 'DEAD_LETTERED', attempts = ?, next_attempt_at = null
            where tenant_id = ? and id = ?
            """)
        .param(attemptNo)
        .param(tenantId)
        .param(notificationId)
        .update();
  }

  /**
   * What one person asked to be told about.
   *
   * @param userId whose
   * @return their subscriptions
   */
  @Transactional(readOnly = true)
  public List<Notifications.Subscription> subscriptions(UUID userId) {
    return jdbc
        .sql(SUBSCRIPTION_COLUMNS + " where tenant_id = ? and user_id = ? order by kind, channel_key")
        .param(TenantContext.require())
        .param(userId)
        .query(NotificationQueries::toSubscription)
        .list();
  }

  /**
   * Records or updates one preference.
   *
   * @param tenantId whose
   * @param userId whose preference
   * @param kind what about
   * @param channelKey which channel
   * @param address where
   * @param enabled whether to deliver
   * @return the stored preference
   */
  @Transactional
  public Notifications.Subscription upsertSubscription(
      UUID tenantId, UUID userId, String kind, String channelKey, String address, boolean enabled) {
    jdbc.sql(
            """
            insert into notification.subscription
                (tenant_id, user_id, kind, channel_key, address, enabled, created_by, updated_by)
            values (?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (tenant_id, user_id, kind, channel_key) do update
                set address = excluded.address,
                    enabled = excluded.enabled,
                    version = notification.subscription.version + 1,
                    updated_at = now(),
                    updated_by = excluded.updated_by
            """)
        .param(tenantId)
        .param(userId)
        .param(kind)
        .param(channelKey)
        .param(address)
        .param(enabled)
        .param(userId)
        .param(userId)
        .update();

    return jdbc
        .sql(
            SUBSCRIPTION_COLUMNS
                + " where tenant_id = ? and user_id = ? and kind = ? and channel_key = ?")
        .param(tenantId)
        .param(userId)
        .param(kind)
        .param(channelKey)
        .query(NotificationQueries::toSubscription)
        .single();
  }

  /**
   * Every try made on one notification.
   *
   * @param notificationId which
   * @return the attempts, oldest first
   */
  @Transactional(readOnly = true)
  public List<Notifications.DeliveryAttempt> attempts(UUID notificationId) {
    return jdbc
        .sql(
            """
            select attempt_no, attempted_at, outcome, detail
            from notification.delivery_attempt
            where tenant_id = ? and notification_id = ?
            order by attempt_no
            """)
        .param(TenantContext.require())
        .param(notificationId)
        .query(
            (ResultSet rs, int row) ->
                new Notifications.DeliveryAttempt(
                    rs.getInt("attempt_no"),
                    rs.getObject("attempted_at", java.time.OffsetDateTime.class).toInstant(),
                    rs.getString("outcome"),
                    rs.getString("detail")))
        .list();
  }

  private static Notifications.QueuedNotification toQueued(ResultSet rs, int row)
      throws SQLException {
    java.time.OffsetDateTime next = rs.getObject("next_attempt_at", java.time.OffsetDateTime.class);
    return new Notifications.QueuedNotification(
        rs.getObject("id", UUID.class),
        rs.getString("channel_key"),
        rs.getString("address"),
        rs.getString("state"),
        rs.getInt("attempts"),
        next == null ? null : next.toInstant());
  }

  private static Notifications.Subscription toSubscription(ResultSet rs, int row)
      throws SQLException {
    return new Notifications.Subscription(
        rs.getObject("id", UUID.class),
        rs.getObject("user_id", UUID.class),
        rs.getString("kind"),
        rs.getString("channel_key"),
        rs.getString("address"),
        rs.getBoolean("enabled"));
  }

  /**
   * One notification waiting to go out.
   *
   * @param id its id
   * @param channelKey which channel
   * @param address where
   * @param subject the subject line
   * @param bodyText the plain text
   * @param bodyHtml the HTML, or {@code null}
   * @param language the recipient's language
   * @param attempts how many tries have been made
   * @param idempotencyKey what the channel deduplicates on, stable across every retry
   * @param webhookTargetId the target this is a delivery to, or {@code null} when it is a message
   *     to a person. Exactly one of the two is set, which V73 states as a constraint rather than as
   *     a convention. The delivery run reads it for one reason: a webhook is signed with the
   *     target's own secret (ADR-0077)
   */
  public record Due(
      UUID id,
      String channelKey,
      String address,
      String subject,
      String bodyText,
      String bodyHtml,
      String language,
      int attempts,
      String idempotencyKey,
      UUID webhookTargetId) {}
}
