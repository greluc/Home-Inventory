/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.plugins.infrastructure;

import de.greluc.homeinv.plugin.api.CallContext;
import de.greluc.homeinv.plugin.api.port.IdentityProvider;
import de.greluc.homeinv.plugin.v1.IdentityProviderBeginRequest;
import de.greluc.homeinv.plugin.v1.IdentityProviderBeginResponse;
import de.greluc.homeinv.plugin.v1.IdentityProviderCompleteRequest;
import de.greluc.homeinv.plugin.v1.IdentityProviderDescribeRequest;
import de.greluc.homeinv.plugin.v1.IdentityProviderDescriptor;
import de.greluc.homeinv.plugin.v1.IdentityProviderGrpc;
import de.greluc.homeinv.plugin.v1.IdentityProviderIdentity;
import io.grpc.Channel;
import io.grpc.StatusRuntimeException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Speaks {@code IdentityProvider} to a plugin (REQ-AUTH-005, ADR-0026).
 *
 * <h2>What the core keeps and what the plugin is given</h2>
 *
 * <p>The {@code state}, the {@code nonce} and the PKCE verifier are <b>the core's</b>. They are
 * minted here, stored here (ADR-0029) and checked here; the plugin receives the challenge and the
 * nonce because the protocol puts them on the wire, and never the verifier until the completion
 * that spends it. A value only the plugin knew would be a value the core could not check, and a
 * sign-in the core cannot check is a redirect with a session attached to it.
 *
 * <h2>The call is for the instance, not for a tenant</h2>
 *
 * <p>A sign-in happens before any tenant is known — that is why {@code identity.app_user} is
 * instance-wide, and a federated sign-in is the same moment one step further out. So the envelope
 * carries {@link de.greluc.homeinv.plugin.api.CallContext.Scope#INSTANCE} and the plugin is
 * resolved through the operator's own grant (ADR-0066) rather than a tenant's.
 */
@Component
public class IdentityProviderAdapter implements PortAdapter<IdentityProvider> {

  @Override
  public Class<IdentityProvider> port() {
    return IdentityProvider.class;
  }

  @Override
  public IdentityProvider adapt(Channel channel, int deadlineMillis) {
    return new Grpc(channel, deadlineMillis);
  }

  /**
   * One plugin's identity provider, over gRPC.
   *
   * @param channel the plugin's channel
   * @param deadlineMillis how long a call may take
   */
  private record Grpc(Channel channel, int deadlineMillis) implements IdentityProvider {

    @Override
    public Descriptor describe(CallContext context) {
      try {
        IdentityProviderDescriptor answer =
            stub()
                .describe(
                    IdentityProviderDescribeRequest.newBuilder()
                        .setContext(PluginWire.contextOf(context))
                        .build());
        return new Descriptor(
            answer.getProviderKey(), answer.getDisplayName(), answer.getPkceRequired());
      } catch (StatusRuntimeException failed) {
        throw PluginWire.failureOf(failed, channel.authority());
      }
    }

    @Override
    public Authorization begin(CallContext context, AuthorizationRequest request) {
      try {
        IdentityProviderBeginResponse answer =
            stub()
                .begin(
                    IdentityProviderBeginRequest.newBuilder()
                        .setContext(PluginWire.contextOf(context))
                        .setRedirectUri(request.redirectUri())
                        .setState(request.state())
                        .setNonce(request.nonce())
                        .setCodeChallenge(request.codeChallenge())
                        .setLoginHint(request.loginHint())
                        .build());
        return new Authorization(answer.getAuthorizationUrl());
      } catch (StatusRuntimeException failed) {
        throw PluginWire.failureOf(failed, channel.authority());
      }
    }

    @Override
    public Identity complete(CallContext context, CompletionRequest request) {
      try {
        IdentityProviderIdentity answer =
            stub()
                .complete(
                    IdentityProviderCompleteRequest.newBuilder()
                        .setContext(PluginWire.contextOf(context))
                        .setCode(request.code())
                        .setCodeVerifier(request.codeVerifier())
                        .setRedirectUri(request.redirectUri())
                        .setNonce(request.nonce())
                        .build());
        return new Identity(
            answer.getSubject(),
            answer.getIssuer(),
            answer.getEmail(),
            answer.getEmailVerified(),
            answer.getDisplayName(),
            Map.copyOf(answer.getClaimsMap()));
      } catch (StatusRuntimeException failed) {
        throw PluginWire.failureOf(failed, channel.authority());
      }
    }

    /** The blocking stub with this call's deadline on it. */
    private IdentityProviderGrpc.IdentityProviderBlockingStub stub() {
      return IdentityProviderGrpc.newBlockingStub(channel)
          .withDeadlineAfter(deadlineMillis, TimeUnit.MILLISECONDS);
    }
  }
}
