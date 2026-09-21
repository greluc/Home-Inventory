/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.eventstream.infrastructure;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import de.greluc.homeinv.eventstream.application.LiveChanges;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Every replica listens, so a change on one reaches a browser connected to another.
 *
 * <p>The other half of {@link LiveChanges}: that one publishes a nudge when a change commits, this
 * one hands the nudge to the streams <b>this</b> replica holds. With a single replica the two are
 * a round trip through Valkey that could have been a method call; with two they are the difference
 * between a feature that works and one that works for half the people using it.
 *
 * <h2>Not in the two roles that have no Valkey</h2>
 *
 * <p>{@code migrate} and {@code bootstrap} are one-shot roles on the {@code internal} segment that
 * <b>talk to PostgreSQL and to nothing else</b> — {@code deploy/services.yaml} says so for both.
 * A listener container is a {@code SmartLifecycle} bean that <b>connects when the context
 * starts</b>, and a connection it cannot make is an exception that cancels the refresh. So this
 * bean, loaded in {@code migrate}, dialled {@code localhost:6379}, was refused, and took the
 * migration down with it — which took the whole profile down, because {@code api} and {@code
 * worker} start only when {@code migrate} exits zero.
 *
 * <p><i>That is what it did on 2026-09-21, between the feature landing and the next smoke run.
 * The unit tests could not see it: they run one context with Valkey present. The rootless smoke
 * suite is the gate that can, and did, under both runtimes.</i>
 *
 * <p>{@code worker} keeps it although it holds no streams: it has Valkey, the subscription costs
 * one connection, and a delivery there finds an empty map and returns. Naming {@code api} instead
 * would be more precise and would put a test profile into production code — {@code EventStreamIT}
 * runs under {@code test} and exists to prove this hop works.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@Profile("!migrate & !bootstrap")
public class LiveChangeSubscriber {

  /**
   * The listener container, subscribed to the one channel.
   *
   * @param connections the Valkey connection factory the application already has
   * @param live where a nudge is handed to the open streams
   * @return the container, started by the context
   */
  @Bean
  RedisMessageListenerContainer liveChangeListener(
      RedisConnectionFactory connections, LiveChanges live) {
    RedisMessageListenerContainer container = new RedisMessageListenerContainer();
    container.setConnectionFactory(connections);
    container.addMessageListener(listener(live), new ChannelTopic(LiveChanges.CHANNEL));
    return container;
  }

  /**
   * Parses a nudge and delivers it.
   *
   * <p>A message is a tenant id, a space and a kind. Deliberately not JSON: it is two tokens, and
   * a parser that can fail is a parser that has to be handled on a path where failure costs a
   * refresh.
   *
   * @param live where to deliver
   * @return the listener
   */
  private static MessageListener listener(LiveChanges live) {
    return (message, pattern) -> {
      String body = new String(message.getBody(), StandardCharsets.UTF_8);
      int space = body.indexOf(' ');
      if (space <= 0) {
        return;
      }
      try {
        live.deliver(UUID.fromString(body.substring(0, space)), body.substring(space + 1));
      } catch (IllegalArgumentException notATenant) {
        // Somebody else publishing on this channel, or a version that spells the
        // message differently. Ignored rather than logged per message: this runs
        // on every change in the deployment.
        log.debug("A live message named no tenant: {}", body);
      }
    };
  }
}
