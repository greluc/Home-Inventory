/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.plugins.infrastructure;

import de.greluc.homeinv.plugin.api.CallContext;
import de.greluc.homeinv.plugin.api.PluginException;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

/**
 * What every adapter needs: the context on the wire, and a status turned back into a failure.
 *
 * <p>Both directions of one translation, in one place, because an adapter that classified statuses
 * its own way would make the circuit breaker's judgement depend on which port a call went through.
 */
public final class PluginWire {

  private PluginWire() {
    throw new AssertionError("A helper, not a thing to instantiate");
  }

  /**
   * The context as the contract carries it.
   *
   * <p>The deadline is deliberately absent: gRPC has one of its own, enforced by both runtimes
   * without anybody remembering to check it, and a second one in the payload would be a second
   * answer to the same question.
   *
   * @param context who the call is for
   * @return the message
   */
  public static de.greluc.homeinv.plugin.v1.CallContext contextOf(CallContext context) {
    return de.greluc.homeinv.plugin.v1.CallContext.newBuilder()
        .setTenantId(context.tenantId().toString())
        .setTraceId(context.traceId())
        .setLanguage(context.language())
        .build();
  }

  /**
   * Turns a failed call into the failure a caller can act on.
   *
   * <p>The mapping is the one the circuit breaker's judgement rests on, so it is made once here
   * rather than per adapter. {@code UNAVAILABLE} and {@code DEADLINE_EXCEEDED} are the two that say
   * "the plugin is not answering" and are therefore the two the breaker counts and a caller may
   * retry; {@code INVALID_ARGUMENT}, {@code NOT_FOUND} and {@code UNIMPLEMENTED} are the plugin
   * answering, and a breaker that counted them would open on a plugin doing its job.
   *
   * @param failure what the call threw
   * @param pluginId which plugin, so that a message names it
   * @return the failure to raise instead
   */
  public static PluginException failureOf(StatusRuntimeException failure, String pluginId) {
    Status.Code code = failure.getStatus().getCode();
    PluginException.Kind kind =
        switch (code) {
          case UNIMPLEMENTED -> PluginException.Kind.UNSUPPORTED;
          case NOT_FOUND -> PluginException.Kind.NOT_FOUND;
          case INVALID_ARGUMENT, OUT_OF_RANGE, FAILED_PRECONDITION ->
              PluginException.Kind.INVALID_ARGUMENT;
          case PERMISSION_DENIED, UNAUTHENTICATED -> PluginException.Kind.DENIED;
          case DEADLINE_EXCEEDED -> PluginException.Kind.DEADLINE_EXCEEDED;
          case UNAVAILABLE, RESOURCE_EXHAUSTED, ABORTED -> PluginException.Kind.UNAVAILABLE;
          default -> PluginException.Kind.INTERNAL;
        };

    // The plugin's own description, which 09 §9.5 asks to be logged, and the
    // code, which is what the mapping above turned on. Not the stack trace: it
    // is the transport's and says nothing about the plugin.
    String detail = failure.getStatus().getDescription();
    return new PluginException(
        kind,
        "Plugin "
            + pluginId
            + " answered "
            + code
            + (detail == null || detail.isBlank() ? "" : ": " + detail),
        failure);
  }
}
