/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: Apache-2.0
 */
package de.greluc.homeinv.plugin.api.port;

import de.greluc.homeinv.plugin.api.CallContext;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Delivers a message to a person (09 §9.2).
 *
 * <p><b>Every implementation is a plugin</b>, e-mail and webhook included, because every one of
 * them talks to a host outside the deployment (ADR-0026). There is no in-core channel and there
 * cannot be one: the core has no outbound route.
 *
 * <p>A stage 1 installation without {@code plugin-smtp} is a valid but reduced deployment — no
 * invitations by mail, no password reset by mail, no notifications. The core says so plainly rather
 * than failing silently (ADR-0028).
 *
 * <p>Stage 1 (REQ-NOTI-001).
 */
public interface NotificationChannel {

  /**
   * What this channel is.
   *
   * @param context who is asking
   * @return its description, which the core shows when somebody chooses where a notification goes
   */
  Descriptor describe(CallContext context);

  /**
   * Delivers one message.
   *
   * <p><b>At-least-once, so this must be idempotent on {@link Message#idempotencyKey()}.</b> A
   * worker that crashed after the mail went out and before the outbox was marked will call again
   * with the same key, and a channel that sends twice has sent twice. An implementation that cannot
   * deduplicate says so in its documentation; the core cannot make it safe from outside.
   *
   * @param context who it is for
   * @param message what to deliver
   * @return what the far side said, for the delivery log
   * @throws de.greluc.homeinv.plugin.api.PluginException when delivery failed. {@link
   *     de.greluc.homeinv.plugin.api.PluginException.Kind#UNAVAILABLE} is retried by the core and
   *     {@link de.greluc.homeinv.plugin.api.PluginException.Kind#INVALID_ARGUMENT} is not, so the
   *     difference between "the mail server is down" and "that is not an address" is the difference
   *     between a retry queue and a dead letter
   */
  Delivery deliver(CallContext context, Message message);

  /**
   * What a channel is.
   *
   * @param channelKey the stable key: {@code email}, {@code webhook}, {@code webpush}, {@code fcm},
   *     {@code apns}
   * @param name what a person sees
   * @param addressSchemes what an address looks like here — {@code mailto}, {@code https}, {@code
   *     token}. The core validates a subscription's address against this before storing it
   * @param supportsHtml whether {@link Message#html()} is used or ignored
   */
  record Descriptor(
      String channelKey, String name, Set<String> addressSchemes, boolean supportsHtml) {}

  /**
   * One message.
   *
   * @param recipient where it goes, in one of the channel's address schemes
   * @param subject the subject line, or empty for channels without one
   * @param text the body as plain text. Always present: it is the fallback every channel can show
   * @param html the body as HTML, or empty. Ignored by a channel that does not support it
   * @param language the recipient's language as an IETF tag. The text is already in it — a channel
   *     translates nothing — but a mail channel puts it in {@code Content-Language}
   * @param headers channel-specific extras: SMTP headers, webhook headers. A channel ignores what
   *     it does not know rather than failing, because the core does not know which channel it will
   *     reach when a rule is written
   * @param idempotencyKey the key to deduplicate on, stable across retries of the same message
   * @param attachments what travels with it, usually empty. Kept small: a channel is not a file
   *     transfer, and a caller with a large file sends a link to it instead
   */
  record Message(
      String recipient,
      String subject,
      String text,
      String html,
      String language,
      Map<String, String> headers,
      String idempotencyKey,
      List<Attachment> attachments) {}

  /**
   * Something sent with a message.
   *
   * @param fileName what to call it
   * @param mediaType what it is
   * @param content the bytes
   */
  record Attachment(String fileName, String mediaType, byte[] content) {}

  /**
   * What the far side said.
   *
   * @param providerMessageId the id the provider gave it, for tracing a delivery afterwards. Empty
   *     when the provider gives none
   * @param detail anything worth recording beside it, in English and fit for a log line — an SMTP
   *     response line, an HTTP status. <b>Never a credential</b>, and never the message body
   * @param deduplicated {@code true} when the channel recognised the idempotency key and did
   *     <i>not</i> send again. The core counts these separately, because a delivery log where every
   *     retry looks like a send is a log that cannot answer "did this person get two mails"
   */
  record Delivery(String providerMessageId, String detail, boolean deduplicated) {}
}
