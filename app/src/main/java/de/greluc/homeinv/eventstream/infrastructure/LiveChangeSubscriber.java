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
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
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
