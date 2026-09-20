/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.plugins.infrastructure;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import java.security.cert.X509Certificate;
import java.util.Optional;
import javax.net.ssl.SSLSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Who is calling on the host channel (ADR-0071).
 *
 * <p>The plugin id is taken from the <b>certificate the caller presented</b> and from nowhere else.
 * Not from a header, not from a field in the request: a header is something a caller writes, and
 * the whole point of this channel being narrow is that what a caller asserts about itself decides
 * nothing.
 *
 * <p>The connection has already been refused if the certificate is not a registered plugin's
 * ({@link RegisteredPlugins}), so by the time a method runs the answer exists. It is still an
 * {@link Optional}, because a call arriving without one is a bug in the wiring and a bug in the
 * wiring should not read as "some plugin".
 */
@Component
@RequiredArgsConstructor
public class CallerIdentity implements ServerInterceptor {

  /** Where the identified plugin sits for the duration of one call. */
  private static final Context.Key<String> CALLER = Context.key("homeinv.plugin.caller");

  private final RegisteredPlugins plugins;

  /**
   * The plugin whose call is running on this thread.
   *
   * @return its id, or empty outside a host-channel call
   */
  public Optional<String> current() {
    return Optional.ofNullable(CALLER.get());
  }

  @Override
  public <Q, A> ServerCall.Listener<Q> interceptCall(
      ServerCall<Q, A> call, Metadata headers, ServerCallHandler<Q, A> next) {
    Optional<String> caller =
        Optional.ofNullable(call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION))
            .flatMap(CallerIdentity::peerOf)
            .flatMap(plugins::identify);

    if (caller.isEmpty()) {
      // Belt as well as braces: the trust manager has already refused an
      // unknown certificate, so reaching here means the two disagree -- which is
      // a reason to close the call rather than to guess.
      call.close(
          io.grpc.Status.UNAUTHENTICATED.withDescription(
              "The caller could not be identified as a registered plugin"),
          new Metadata());
      return new ServerCall.Listener<>() {};
    }
    return Contexts.interceptCall(
        Context.current().withValue(CALLER, caller.get()), call, headers, next);
  }

  /**
   * The certificate at the other end of a TLS session.
   *
   * @param session the session
   * @return the peer's certificate, or empty when it presented none
   */
  private static Optional<X509Certificate> peerOf(SSLSession session) {
    try {
      java.security.cert.Certificate[] chain = session.getPeerCertificates();
      return chain.length == 0 || !(chain[0] instanceof X509Certificate certificate)
          ? Optional.empty()
          : Optional.of(certificate);
    } catch (javax.net.ssl.SSLPeerUnverifiedException unverified) {
      return Optional.empty();
    }
  }
}
