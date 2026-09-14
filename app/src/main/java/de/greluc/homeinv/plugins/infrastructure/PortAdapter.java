/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.plugins.infrastructure;

import io.grpc.Channel;

/**
 * Turns a channel into one of the ports a plugin implements.
 *
 * <p>One implementation per port, each a bean. {@code ExtensionRegistry} picks the one whose {@link
 * #port()} matches what was asked for, so adding a port means adding a class here and changing
 * nothing else — which is the shape REQ-PLG-010 asks for from the other side as well.
 *
 * <p>An adapter's only job is translation: the port's Java types to the contract's messages and
 * back, and a gRPC status to a {@link de.greluc.homeinv.plugin.api.PluginException} with the kind
 * that says what happened. It applies no deadline policy, no retry and no breaker — those are the
 * envelope's ({@code PluginResilience}), and an adapter that had its own would be a second answer
 * to the same question.
 *
 * @param <T> the port from {@code de.greluc.homeinv.plugin.api.port}
 */
public interface PortAdapter<T> {

  /**
   * Which port this adapter speaks.
   *
   * @return the port interface
   */
  Class<T> port();

  /**
   * Builds an implementation that calls through this channel.
   *
   * @param channel the plugin's channel, already mutually authenticated and pinned
   * @param deadlineMillis how long each call may take, decided by the operator's limit and the
   *     manifest's request. Applied per call rather than per channel, because a deadline set on a
   *     stub once counts from when the stub was made
   * @return the implementation, unwrapped. {@code ExtensionRegistry} wraps it
   */
  T adapt(Channel channel, int deadlineMillis);
}
