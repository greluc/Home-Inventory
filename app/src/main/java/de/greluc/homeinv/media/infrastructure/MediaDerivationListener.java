/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.media.infrastructure;

import de.greluc.homeinv.media.api.MediaObjectStored;
import de.greluc.homeinv.media.application.DerivativeGenerator;
import de.greluc.homeinv.platform.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The {@code worker}'s consumer for {@link MediaObjectStored}.
 *
 * <h2>Why the binding is declared here and not in a configuration class</h2>
 *
 * <p>Because the queue belongs to the consumer. {@code api} publishes to an exchange and knows
 * nothing about who listens; a queue declared centrally would be a queue that exists whether or not
 * anything reads it, and a queue nobody reads fills up.
 *
 * <h2>The tenant context is established from the message</h2>
 *
 * <p>A consumer has no session and no request, so {@code TenantContext} is empty when a message
 * arrives — and with it empty, every query the derivation makes returns zero rows rather than
 * failing, which is row-level security working and looks exactly like a missing object. The tenant
 * therefore travels in the payload and is pushed here, around the whole unit of work.
 *
 * <p>It is <b>not</b> read from a header a publisher could set independently of the body: the
 * tenant and the object have to come from the same place, or a message could name one tenant in its
 * header and another's object in its body.
 *
 * <h2>Only in the worker</h2>
 *
 * <p>{@code api} runs the same image and must not consume this queue. It would work — the code is
 * the same — and it would put the encodes back on the process that serves requests, which is what
 * moving them here was for.
 */
@Slf4j
@Component
@Profile("worker")
@RequiredArgsConstructor
public class MediaDerivationListener {

  private final DerivativeGenerator generator;

  /**
   * Derives the variants of one stored media object.
   *
   * @param event what was stored
   */
  @RabbitListener(
      bindings =
          @QueueBinding(
              // Durable and named, so a worker that is restarted finds the work
              // that arrived while it was down. An anonymous exclusive queue
              // would lose every message published during a deployment.
              value = @Queue(name = "homeinv.media.derivation", durable = "true"),
              exchange = @Exchange(name = "homeinv.media", type = "topic", durable = "true"),
              key = "media-object-stored"))
  public void onMediaObjectStored(MediaObjectStored event) {
    log.debug("Deriving variants for media object {}", event.mediaObjectId());
    TenantContext.runAs(
        event.tenantId(), () -> generator.derive(event.tenantId(), event.mediaObjectId()));
  }
}
