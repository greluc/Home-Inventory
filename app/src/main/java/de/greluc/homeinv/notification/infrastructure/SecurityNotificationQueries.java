/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.notification.infrastructure;

import de.greluc.homeinv.notification.api.SecurityNotifications;
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
 * Every statement the account-level half of the {@code notification} block runs (ADR-0066).
 *
 * <p>Not one query here mentions a tenant, and that is the difference from {@link
 * NotificationQueries} rather than an omission: these rows belong to an account, the account may
 * belong to no tenant, and a policy keyed on a context that does not exist would hide the work from
 * the run that has to do it (07 §7.1).
 */
@Component
@RequiredArgsConstructor
public class SecurityNotificationQueries {

  private static final String COLUMNS =
      "select id, user_id, kind, channel_key, address, state, attempts, next_attempt_at, created_at"
          + " from notification.security_notification";

  private final JdbcClient jdbc;

  /**
   * Queues one account notification, or returns the one already queued under this key.
   *
   * <p>Idempotent on {@code idempotency_key}, which is unique across the instance because there is
   * no tenant to scope it with. The first attempt is due immediately: an account event is the kind
   * of news that is worth nothing late.
   *
   * @param message what to tell them, and where
   * @return the row, new or existing
   */
  @Transactional
  public SecurityNotifications.Queued queue(
      SecurityNotifications.NewSecurityNotification message) {
    jdbc.sql(
            """
            insert into notification.security_notification
                (user_id, kind, address, subject, body_text, body_html, language,
                 idempotency_key, next_attempt_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, now())
            on conflict (idempotency_key) do nothing
            """)
        .param(message.userId())
        .param(message.kind())
        .param(message.address())
        .param(message.subject() == null ? "" : message.subject())
        .param(message.bodyText())
        .param(message.bodyHtml())
        .param(message.language() == null ? "en" : message.language())
        .param(message.idempotencyKey())
        .update();

    return jdbc
        .sql(COLUMNS + " where idempotency_key = ?")
        .param(message.idempotencyKey())
        .query(SecurityNotificationQueries::toQueued)
        .single();
  }

  /**
   * What is waiting and due.
   *
   * @param now the present
   * @param batch how many at most, so a backlog does not become one long transaction
   * @return the outstanding work, oldest first
   */
  @Transactional(readOnly = true)
  public List<Due> due(Instant now, int batch) {
    return jdbc
        .sql(
            """
            select id, channel_key, address, subject, body_text, body_html, language,
                   attempts, idempotency_key
            from notification.security_notification
            where state = 'QUEUED' and next_attempt_at <= ?
            order by next_attempt_at
            limit ?
            """)
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
                    rs.getString("idempotency_key")))
        .list();
  }

  /**
   * Records one attempt and what came of it.
   *
   * @param notificationId which notification
   * @param attemptNo which try, from one
   * @param outcome {@code ACCEPTED}, {@code DEDUPLICATED}, {@code REFUSED} or {@code FAILED}
   * @param detail what the far side said, truncated to what the column holds
   */
  @Transactional
  public void recordAttempt(UUID notificationId, int attemptNo, String outcome, String detail) {
    jdbc.sql(
            """
            insert into notification.security_delivery_attempt
                (notification_id, attempt_no, outcome, detail)
            values (?, ?, ?, ?)
            on conflict (notification_id, attempt_no) do nothing
            """)
        .param(notificationId)
        .param(attemptNo)
        .param(outcome)
        .param(
            detail == null || detail.isBlank()
                ? null
                : detail.substring(0, Math.min(detail.length(), 1000)))
        .update();
  }

  /**
   * Marks one delivered.
   *
   * @param notificationId which notification
   * @param attemptNo the attempt that succeeded
   * @param providerMessageId what the channel called it, for tracing with whoever runs the server
   */
  @Transactional
  public void markDelivered(UUID notificationId, int attemptNo, String providerMessageId) {
    jdbc.sql(
            """
            update notification.security_notification
               set state = 'DELIVERED', attempts = ?, next_attempt_at = null,
                   delivered_at = now(), provider_message_id = ?
             where id = ?
            """)
        .param(attemptNo)
        .param(providerMessageId)
        .param(notificationId)
        .update();
  }

  /**
   * Puts one back in the queue for later.
   *
   * @param notificationId which notification
   * @param attemptNo how many tries have been made
   * @param nextAttemptAt when the next one is due
   */
  @Transactional
  public void reschedule(UUID notificationId, int attemptNo, Instant nextAttemptAt) {
    jdbc.sql(
            """
            update notification.security_notification
               set attempts = ?, next_attempt_at = ?
             where id = ?
            """)
        .param(attemptNo)
        .param(java.sql.Timestamp.from(nextAttemptAt))
        .param(notificationId)
        .update();
  }

  /**
   * Stops trying, and leaves every attempt readable beside the row.
   *
   * @param notificationId which notification
   * @param attemptNo how many tries were made in the end
   */
  @Transactional
  public void deadLetter(UUID notificationId, int attemptNo) {
    jdbc.sql(
            """
            update notification.security_notification
               set state = 'DEAD_LETTERED', attempts = ?, next_attempt_at = null
             where id = ?
            """)
        .param(attemptNo)
        .param(notificationId)
        .update();
  }

  /**
   * What one account has been told.
   *
   * @param userId whose account
   * @param limit how many at most
   * @return the notifications, most recent first
   */
  @Transactional(readOnly = true)
  public List<SecurityNotifications.Queued> of(UUID userId, int limit) {
    return jdbc
        .sql(COLUMNS + " where user_id = ? order by created_at desc limit ?")
        .param(userId)
        .param(limit)
        .query(SecurityNotificationQueries::toQueued)
        .list();
  }

  /**
   * One notification by id.
   *
   * @param notificationId which one
   * @return it, or empty
   */
  @Transactional(readOnly = true)
  public Optional<SecurityNotifications.Queued> byId(UUID notificationId) {
    return jdbc
        .sql(COLUMNS + " where id = ?")
        .param(notificationId)
        .query(SecurityNotificationQueries::toQueued)
        .optional();
  }

  /**
   * What happened on each try.
   *
   * @param notificationId which notification
   * @return the attempts, oldest first
   */
  @Transactional(readOnly = true)
  public List<SecurityNotifications.Attempt> attempts(UUID notificationId) {
    return jdbc
        .sql(
            """
            select attempt_no, attempted_at, outcome, detail
            from notification.security_delivery_attempt
            where notification_id = ?
            order by attempt_no
            """)
        .param(notificationId)
        .query(
            (ResultSet rs, int row) ->
                new SecurityNotifications.Attempt(
                    rs.getInt("attempt_no"),
                    rs.getTimestamp("attempted_at").toInstant(),
                    rs.getString("outcome"),
                    rs.getString("detail")))
        .list();
  }

  private static SecurityNotifications.Queued toQueued(ResultSet rs, int rowNum)
      throws SQLException {
    java.sql.Timestamp next = rs.getTimestamp("next_attempt_at");
    return new SecurityNotifications.Queued(
        rs.getObject("id", UUID.class),
        rs.getObject("user_id", UUID.class),
        rs.getString("kind"),
        rs.getString("channel_key"),
        rs.getString("address"),
        rs.getString("state"),
        rs.getInt("attempts"),
        next == null ? null : next.toInstant(),
        rs.getTimestamp("created_at").toInstant());
  }

  /**
   * One notification the delivery run is about to try.
   *
   * @param id which notification
   * @param channelKey which channel
   * @param address where
   * @param subject the subject line
   * @param bodyText the message
   * @param bodyHtml the message as HTML, or null
   * @param language the recipient's language
   * @param attempts how many tries have already been made
   * @param idempotencyKey what the channel deduplicates on
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
      String idempotencyKey) {}
}
