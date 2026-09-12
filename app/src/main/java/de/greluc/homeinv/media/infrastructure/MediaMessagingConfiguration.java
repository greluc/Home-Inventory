/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.media.infrastructure;

import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * The exchange media events are published to.
 *
 * <h2>Why the publisher declares it</h2>
 *
 * <p>The consumer declares its own queue and binds it — a queue belongs to whoever reads it. The
 * exchange is different: {@code api} publishes to it whether or not a worker has ever run, and
 * publishing to an exchange that does not exist is not an error a publisher sees. The message goes
 * nowhere, the channel is closed asynchronously, and the first symptom is thumbnails that never
 * appear in a deployment where the worker started second.
 *
 * <p>Declared in both roles, therefore, and durable in both: a broker restart must not take the
 * topology with it.
 */
@Configuration(proxyBeanMethods = false)
public class MediaMessagingConfiguration {

  /** The exchange named by {@code MediaObjectStored}'s {@code @Externalized} routing target. */
  public static final String EXCHANGE = "homeinv.media";

  /** The routing key {@code MediaObjectStored} is published with and the scan listener binds. */
  public static final String SCAN_ROUTING_KEY = "media-object-stored";

  /** Where a scan that reached no verdict waits before it is tried again. */
  public static final String SCAN_RETRY_QUEUE = "homeinv.media.scan-retry";

  /**
   * How long a failed scan waits before the broker hands it back.
   *
   * <p>Five minutes: long enough that a restarting {@code clamav} — which loads a gigabyte of
   * signatures before it answers — is up again, and short enough that an upload is not left
   * unretrievable for an hour over a container restart.
   */
  public static final int RETRY_DELAY_MS = 300_000;

  /**
   * JSON on the wire, in both directions.
   *
   * <p>Without this bean Spring AMQP's default converter hands a listener a {@code byte[]} and the
   * message fails to convert — while the publisher, which goes through Spring Modulith, has already
   * written JSON with a content type saying so. The two halves disagreeing is the failure mode, and
   * it surfaces only at the consumer, at run time, on the first message.
   *
   * <p>JSON rather than Java serialisation, and not as a preference: a serialised Java object on a
   * queue is a deserialisation gadget waiting for a consumer, and the event schema is versioned and
   * checked for breaking changes in CI ({@code CLAUDE.md}, "When you change… an event"), which
   * needs a format a schema can describe.
   *
   * @return the converter both the template and the listener container use
   */
  @Bean
  public MessageConverter amqpMessageConverter() {
    return new JacksonJsonMessageConverter();
  }

  /**
   * The topic exchange media events are routed through.
   *
   * <p>Topic rather than direct, because stage 1 adds {@code MediaScanFailed} and
   * {@code MediaDeleted} to the same exchange (04 §4.1) and a consumer will want some of them and
   * not others. Choosing it now costs nothing and avoids a topology migration on a live broker.
   *
   * @return the exchange, declared by {@code RabbitAdmin} at startup
   */
  @Bean
  public TopicExchange mediaExchange() {
    return ExchangeBuilder.topicExchange(EXCHANGE).durable(true).build();
  }

  /**
   * The queue a failed scan waits in before it is tried again (ADR-0054).
   *
   * <h2>Why the catch-up is a queue and not a scheduled sweep</h2>
   *
   * <p>Because a sweep cannot be written. A run that asks "which objects are {@code SCAN_FAILED}?"
   * is a query across every tenant, and {@code homeinv_app} has no {@code BYPASSRLS} and no way to
   * enumerate tenants — a query with no {@code app.tenant_id} returns zero rows, which is row-level
   * security working exactly as {@code CLAUDE.md} rule 2 requires. The only place the tenant is
   * available outside a request is the message that carried it, so the retry carries it too.
   *
   * <p>Nothing consumes this queue. Messages sit here for {@link #RETRY_DELAY_MS} and are then
   * dead-lettered back onto the media exchange with the routing key the scan listener binds, which
   * is the standard way to express a delay in a broker that has no delayed exchange of its own. The
   * effect is a slow, bounded retry loop rather than the hot redelivery loop an unacknowledged
   * message would produce, and the object is visibly {@code SCAN_FAILED} in between rather than
   * indistinguishable from one that has simply not been scanned yet.
   *
   * @return the delay queue, declared by {@code RabbitAdmin} at startup
   */
  @Bean
  @Profile("worker")
  public Queue mediaScanRetryQueue() {
    return QueueBuilder.durable(SCAN_RETRY_QUEUE)
        .ttl(RETRY_DELAY_MS)
        .deadLetterExchange(EXCHANGE)
        .deadLetterRoutingKey(SCAN_ROUTING_KEY)
        .build();
  }
}
