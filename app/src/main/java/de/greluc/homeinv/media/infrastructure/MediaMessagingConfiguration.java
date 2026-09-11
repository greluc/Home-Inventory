/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.media.infrastructure;

import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
}
