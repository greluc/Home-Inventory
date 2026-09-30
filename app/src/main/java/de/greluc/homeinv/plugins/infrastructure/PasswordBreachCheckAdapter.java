/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.plugins.infrastructure;

import de.greluc.homeinv.plugin.api.CallContext;
import de.greluc.homeinv.plugin.api.port.PasswordBreachCheck;
import de.greluc.homeinv.plugin.v1.PasswordBreachCheckGrpc;
import de.greluc.homeinv.plugin.v1.PasswordBreachPrefixRequest;
import de.greluc.homeinv.plugin.v1.PasswordBreachRange;
import io.grpc.Channel;
import io.grpc.StatusRuntimeException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Speaks {@code PasswordBreachCheck} to a plugin (ADR-0067, REQ-SEC-011).
 *
 * <p>Nothing here sees a password. What goes out is a five-character SHA-1 prefix and what comes
 * back is a list of suffixes; the comparison happens in the core. An adapter that took the password
 * would be the whole objection to this port, so it does not have one to pass on.
 */
@Component
public class PasswordBreachCheckAdapter implements PortAdapter<PasswordBreachCheck> {

  @Override
  public Class<PasswordBreachCheck> port() {
    return PasswordBreachCheck.class;
  }

  @Override
  public PasswordBreachCheck adapt(Channel channel, int deadlineMillis) {
    return new Grpc(channel, deadlineMillis);
  }

  /**
   * One plugin's breach service, over gRPC.
   *
   * @param channel the plugin's channel
   * @param deadlineMillis how long a call may take
   */
  private record Grpc(Channel channel, int deadlineMillis) implements PasswordBreachCheck {

    @Override
    public Range suffixesFor(CallContext context, String sha1Prefix) {
      try {
        PasswordBreachRange answer =
            PasswordBreachCheckGrpc.newBlockingStub(channel)
                .withDeadlineAfter(deadlineMillis, TimeUnit.MILLISECONDS)
                .suffixesFor(
                    PasswordBreachPrefixRequest.newBuilder()
                        .setContext(PluginWire.contextOf(context))
                        .setSha1Prefix(sha1Prefix)
                        .build());
        return new Range(List.copyOf(answer.getSuffixesList()));
      } catch (StatusRuntimeException failed) {
        throw PluginWire.failureOf(failed, channel.authority());
      }
    }
  }
}
