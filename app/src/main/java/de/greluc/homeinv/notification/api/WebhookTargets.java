/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.notification.api;

import jakarta.annotation.Nullable;
import de.greluc.homeinv.platform.EventType;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

/**
 * Where a tenant wants to be told that something changed (REQ-API-010).
 *
 * <p>A target is three things: a URL, the {@link EventType}s it wants, and a signing secret it
 * shares with whoever runs the receiver. It is level-1 configuration — 09 §9.1 lists webhook
 * targets there — so a tenant administrator adds one without a release.
 *
 * <h2>Nothing here delivers anything</h2>
 *
 * <p>Creating a target writes a row. When a matching event commits, the block queues one ordinary
 * {@code notification.notification} and the existing delivery run hands it to {@code
 * plugin-webhook}, which signs it and posts it (ADR-0026: the core makes no outbound call at all,
 * and a webhook target is the one URL in this system that a <b>tenant</b> chooses).
 *
 * <p>So a target that has never been reached and a target whose receiver is down look the same from
 * here, and the answer to both is the delivery log: {@link #deliveries} shows every attempt, what
 * the far side said, and whether it was given up on.
 *
 * <h2>What a delivery carries</h2>
 *
 * <p>The event type, the moment and the subject's id — never a name and never a field. A receiver
 * re-reads through the ordinary API, which applies the ordinary permissions (ADR-0078).
 */
public interface WebhookTargets {

  /**
   * The tenant's targets, oldest first.
   *
   * @return the targets, enabled and disabled alike. Never the signing secret — {@link
   *     WebhookTargetView} has no field for it, which is the only way to be sure it is not returned
   *     by accident
   */
  List<WebhookTargetView> list();

  /**
   * One target.
   *
   * @param id which one
   * @return it
   * @throws de.greluc.homeinv.platform.NotFoundException when this tenant has no such target
   */
  WebhookTargetView get(UUID id);

  /**
   * Creates a target.
   *
   * @param command where, what for, and the secret to sign with
   * @param actor who is creating it
   * @return the stored target
   * @throws IllegalArgumentException when the URL is not {@code https}, when the secret is too
   *     short to be one, or when an event type is not one this deployment raises — all three
   *     refused here, by the person who can still fix them, rather than accepted and then silently
   *     never delivering
   * @throws WebhookTargetUrlTakenException when this tenant already has a target at that URL
   */
  WebhookTargetView create(NewWebhookTarget command, UUID actor);

  /**
   * Replaces a target.
   *
   * @param id which one
   * @param command what it should now say. A {@code null} secret keeps the stored one — a form that
   *     cannot show the secret must not clear it by saving
   * @param expectedVersion the version the caller acted on; empty skips the check (REQ-API-004)
   * @param actor who is changing it
   * @return the stored target
   * @throws de.greluc.homeinv.platform.NotFoundException when this tenant has no such target
   * @throws de.greluc.homeinv.platform.StaleVersionException when somebody else changed it first
   * @throws WebhookTargetUrlTakenException when another target of this tenant is at that URL
   */
  WebhookTargetView update(
      UUID id, NewWebhookTarget command, OptionalLong expectedVersion, UUID actor);

  /**
   * Removes a target and everything that was delivered to it.
   *
   * <p>Its deliveries and their attempts go with it. A delivery log about a receiver nobody can
   * name any more answers no question, and keeping one would be retention without a reason — the
   * argument REQ-SRCH-008 makes about a saved search. Pausing instead of removing is what {@code
   * enabled} is for.
   *
   * @param id which one
   * @param actor who is removing it
   * @throws de.greluc.homeinv.platform.NotFoundException when this tenant has no such target
   */
  void remove(UUID id, UUID actor);

  /**
   * What was delivered to one target, newest first (REQ-NOTI-005).
   *
   * @param id which target
   * @param limit how many at most
   * @return the deliveries, with their state and how many attempts each has taken
   * @throws de.greluc.homeinv.platform.NotFoundException when this tenant has no such target
   */
  List<Notifications.QueuedNotification> deliveries(UUID id, int limit);

  /**
   * What a target should say.
   *
   * @param url where to post, {@code https} only
   * @param description what a person calls it, or {@code null}
   * @param eventTypes which events it wants, at least one
   * @param signingSecret what to sign with, or {@code null} on an update to keep the stored one
   * @param enabled whether it receives anything
   */
  record NewWebhookTarget(
      String url,
      String description,
      Set<EventType> eventTypes,
      String signingSecret,
      boolean enabled) {}

  /**
   * The tenant already has a target at that URL.
   *
   * <p>Its own exception rather than a constraint violation reaching the surface as a 500: the
   * address is unique per tenant, and what the caller has to change is the address — which the
   * message says. Answered {@code 409} with {@code resource-exists}.
   *
   * <p>A second row for one URL would be a second copy of everything it already receives, which is
   * a mistake and not a wish: one target names as many event types as it likes.
   */
  class WebhookTargetUrlTakenException extends RuntimeException {

    /**
     * Names the address that is taken.
     *
     * @param url the address already configured
     */
    public WebhookTargetUrlTakenException(String url) {
      super(
          "This tenant already has a webhook target at "
              + url
              + ". One target can name as many event types as it likes.");
    }
  }

  /**
   * A stored target, without its secret.
   *
   * @param id its id
   * @param url where deliveries go
   * @param description what a person calls it, or {@code null}
   * @param eventTypes which events it receives
   * @param enabled whether it receives anything
   * @param version for {@code If-Match}
   * @param createdAt when it was added
   * @param updatedAt when it last changed
   */
  record WebhookTargetView(
      UUID id,
      String url,
      @Nullable String description,
      Set<EventType> eventTypes,
      boolean enabled,
      long version,
      Instant createdAt,
      Instant updatedAt) {}
}
