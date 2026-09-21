/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.notification.api;

import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Telling somebody something (REQ-NOTI-002, REQ-NOTI-005, REQ-NOTI-006, 04 {@code notification}).
 *
 * <h2>Raising is not sending</h2>
 *
 * <p>{@link #raise} writes a row and returns. Delivery happens afterwards, in the worker, through
 * whichever plugin implements the channel — because <b>every</b> channel is a plugin, e-mail
 * included, and the core has no outbound route at all (ADR-0026).
 *
 * <p>That separation is what makes an invitation survive a mail server being down: the invitation
 * is created, the notification is queued, and the delivery is retried. It is also why a caller
 * never learns from this port whether a message arrived — it has not been tried yet.
 *
 * <h2>What a person asked for</h2>
 *
 * <p>Nothing is delivered to somebody who did not ask for it: {@link #raise} resolves the
 * subscriptions for the kind and queues one notification per channel they chose. A kind nobody
 * subscribed to produces nothing, and says so by returning an empty list rather than by failing.
 */
public interface Notifications {

  /**
   * Queues a notification on every channel the person subscribed to for this kind.
   *
   * <p>Idempotent on {@link NewNotification#idempotencyKey()} per tenant: raising the same thing
   * twice queues it once. Delivery is at-least-once further down, and the channel deduplicates on
   * the same key (REQ-NFR-016).
   *
   * @param notification what to tell them
   * @return the queued notifications, one per subscribed channel, empty when they subscribed to
   *     none
   */
  List<QueuedNotification> raise(NewNotification notification);

  /**
   * What one person has asked to be told about.
   *
   * @param userId whose preferences
   * @return their subscriptions, enabled and disabled alike — a disabled one holds the address they
   *     typed, so turning it back on does not ask again
   */
  List<Subscription> subscriptions(UUID userId);

  /**
   * Records or updates what somebody wants on one channel for one kind.
   *
   * @param userId whose preference
   * @param kind what about
   * @param channelKey which channel, as a plugin's manifest names it
   * @param address where, in that channel's address scheme
   * @param enabled whether to deliver
   * @return the stored preference
   */
  Subscription subscribe(
      UUID userId, String kind, String channelKey, String address, boolean enabled);

  /**
   * One notification, whatever became of it.
   *
   * @param notificationId which one
   * @return it, or empty when this tenant has no such notification
   */
  java.util.Optional<QueuedNotification> notification(UUID notificationId);

  /**
   * What happened on each try (REQ-NOTI-005).
   *
   * @param notificationId which notification
   * @return the attempts, oldest first
   */
  List<DeliveryAttempt> attempts(UUID notificationId);

  /**
   * Something to tell somebody.
   *
   * @param userId who to tell
   * @param kind what about, as the raising block names it: {@code invitation}, {@code
   *     security.password-changed}
   * @param subject the subject line, for channels that have one
   * @param bodyText the message as plain text. Always present: it is the fallback every channel can
   *     show
   * @param bodyHtml the message as HTML, or {@code null}
   * @param language the recipient's language as an IETF tag. The text is already in it — nothing
   *     here translates
   * @param idempotencyKey what makes raising this twice raise it once, and what the channel
   *     deduplicates on
   */
  record NewNotification(
      UUID userId,
      String kind,
      String subject,
      String bodyText,
      String bodyHtml,
      String language,
      String idempotencyKey) {}

  /**
   * A notification waiting to be delivered.
   *
   * @param id its id
   * @param channelKey which channel it is going out on
   * @param address where
   * @param state {@code QUEUED}, {@code DELIVERED} or {@code DEAD_LETTERED}
   * @param attempts how many tries have been made
   * @param nextAttemptAt when the next one is due, or {@code null} once it is finished
   */
  record QueuedNotification(
      UUID id, String channelKey, String address, String state, int attempts, @Nullable Instant nextAttemptAt) {}

  /**
   * What somebody asked to be told about, and where.
   *
   * @param id its id
   * @param userId whose
   * @param kind what about
   * @param channelKey which channel
   * @param address where
   * @param enabled whether it is on
   */
  record Subscription(
      UUID id, UUID userId, String kind, String channelKey, String address, boolean enabled) {}

  /**
   * One try.
   *
   * @param attemptNo which try, from one
   * @param attemptedAt when
   * @param outcome {@code ACCEPTED}, {@code DEDUPLICATED}, {@code REFUSED} or {@code FAILED}
   * @param detail what the far side said, or what went wrong. Never a credential, never the body
   */
  record DeliveryAttempt(int attemptNo, Instant attemptedAt, String outcome, String detail) {}
}
