/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.notification.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Telling somebody something about their <b>account</b> (REQ-NOTI-004, ADR-0066).
 *
 * <h2>Why this is not {@link Notifications}</h2>
 *
 * <p>{@link Notifications} is a tenant's queue: it resolves subscriptions, queues one message per
 * channel the person chose, and delivers under that tenant's grants. Every one of those is wrong
 * here, and each for its own reason:
 *
 * <ul>
 *   <li><b>There may be no tenant.</b> A password reset is asked for at the login page, and the
 *       account may be a member of several tenants or of none.
 *   <li><b>There is no subscription to consult.</b> {@code REQ-NOTI-004} says these are always
 *       reported and cannot be switched off by a user or a tenant administrator, so the address on
 *       the account is where they go.
 *   <li><b>A tenant's delivery history is not the place for them.</b> What a person is told about
 *       their own account is not a tenant's business, even a tenant they belong to.
 * </ul>
 *
 * <h2>Raising is still not sending</h2>
 *
 * <p>As with the tenant queue, {@link #raise} writes a row and returns. Delivery happens in the
 * worker, through whichever plugin serves the channel under an <b>instance-level</b> grant
 * (ADR-0066) — so an installation whose operator has granted none delivers nothing and says so in
 * the delivery log, rather than silently dropping the message.
 */
public interface SecurityNotifications {

  /**
   * Queues one account-level message.
   *
   * <p>Idempotent on {@code idempotencyKey}, which is global here because there is no tenant to
   * scope it with: raising the same event twice queues it once.
   *
   * <p>The address is taken now rather than at delivery time. That is deliberate and is half of
   * what {@code REQ-SEC-018} asks for: a password reset tells the address the account had when the
   * reset was asked for, so somebody who has taken the account over and changed the address cannot
   * redirect the warning.
   *
   * @param message what to tell them, and where
   * @return the queued notification, or the one already queued under this key
   */
  Queued raise(NewSecurityNotification message);

  /**
   * What this account has been told, most recent first.
   *
   * @param userId whose account
   * @param limit how many at most
   * @return the notifications, whatever became of each
   */
  List<Queued> of(UUID userId, int limit);

  /**
   * What happened on each try (REQ-NOTI-005).
   *
   * @param notificationId which notification
   * @return the attempts, oldest first
   */
  List<Attempt> attempts(UUID notificationId);

  /**
   * Something to tell somebody about their account.
   *
   * @param userId whose account
   * @param kind what happened, as the raising block names it: {@code security.password-reset},
   *     {@code security.password-changed}
   * @param address where to tell them, as it stands now
   * @param subject the subject line
   * @param bodyText the message as plain text. Always present: it is the fallback every channel can
   *     show
   * @param bodyHtml the message as HTML, or {@code null}
   * @param language the recipient's language as an IETF tag. The text is already in it — nothing
   *     here translates
   * @param idempotencyKey what makes raising this twice raise it once, and what the channel
   *     deduplicates on
   */
  record NewSecurityNotification(
      UUID userId,
      String kind,
      String address,
      String subject,
      String bodyText,
      String bodyHtml,
      String language,
      String idempotencyKey) {}

  /**
   * One account notification, whatever became of it.
   *
   * @param id its id
   * @param userId whose account
   * @param kind what happened
   * @param channelKey which channel it goes out on
   * @param address where — never shown to anybody but the account holder and the operator
   * @param state {@code QUEUED}, {@code DELIVERED} or {@code DEAD_LETTERED}
   * @param attempts how many tries have been made
   * @param nextAttemptAt when the next one is due, or {@code null} once it is finished
   * @param createdAt when it was raised
   */
  record Queued(
      UUID id,
      UUID userId,
      String kind,
      String channelKey,
      String address,
      String state,
      int attempts,
      Instant nextAttemptAt,
      Instant createdAt) {}

  /**
   * One delivery attempt.
   *
   * @param attemptNo which try this was, from one
   * @param attemptedAt when
   * @param outcome {@code ACCEPTED}, {@code DEDUPLICATED}, {@code REFUSED} or {@code FAILED}
   * @param detail what the far side said, never a credential and never the message body
   */
  record Attempt(int attemptNo, Instant attemptedAt, String outcome, String detail) {}
}
