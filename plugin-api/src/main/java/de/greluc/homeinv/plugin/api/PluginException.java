/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: Apache-2.0
 */
package de.greluc.homeinv.plugin.api;

import java.io.Serial;
import java.util.Objects;

/**
 * What a port throws when it cannot do what was asked.
 *
 * <p>One exception with a {@link Kind} rather than a family of classes, because the kind has to
 * survive a process boundary: it is carried as a gRPC status and read back on the other side, and a
 * class name does not travel. The kinds are the distinctions the caller acts on differently —
 * everything else is {@link Kind#INTERNAL}, which is honest about being unclassified.
 *
 * <p><b>{@link #retryable()} is the field the core actually branches on.</b> A notification worker
 * needs to know whether to try again, and "unavailable" and "invalid input" answer that question
 * differently no matter what went wrong underneath.
 */
public class PluginException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** What kind of failure this is. */
  private final Kind kind;

  /**
   * Fails with a kind and a message.
   *
   * @param kind what kind of failure it is
   * @param message what happened, in English, without a credential or a tenant's data in it — this
   *     text reaches the core's log and may reach an administrator
   */
  public PluginException(Kind kind, String message) {
    this(kind, message, null);
  }

  /**
   * Fails with a kind, a message and a cause.
   *
   * @param kind what kind of failure it is
   * @param message what happened
   * @param cause what caused it, or {@code null}. It does not cross the process boundary: only the
   *     kind and the message do
   */
  public PluginException(Kind kind, String message, Throwable cause) {
    super(message, cause);
    this.kind = Objects.requireNonNull(kind, "A failure without a kind cannot be acted on");
  }

  /**
   * What kind of failure this is.
   *
   * @return the kind, never {@code null}
   */
  public Kind kind() {
    return kind;
  }

  /**
   * Whether trying the same call again could succeed.
   *
   * <p>Derived from the kind rather than set per throw, so that two plugins cannot disagree about
   * whether the same situation is worth a retry. {@link Kind#UNAVAILABLE} and {@link
   * Kind#DEADLINE_EXCEEDED} are the two that pass: something outside the call may have changed.
   * {@link Kind#INTERNAL} is deliberately not retryable — a plugin that does not know what went
   * wrong cannot promise that repeating it is safe.
   *
   * @return {@code true} when the caller may try again
   */
  public boolean retryable() {
    return kind == Kind.UNAVAILABLE || kind == Kind.DEADLINE_EXCEEDED;
  }

  /** The distinctions a caller acts on differently. */
  public enum Kind {
    /**
     * The port does not do this at all — an unknown code scheme, an output format it cannot write.
     * Permanent for this plugin and a reason to ask the next one in priority order.
     */
    UNSUPPORTED,
    /** What was asked for is not there. A blob, a printer, a job. */
    NOT_FOUND,
    /** The arguments do not make sense. Retrying with the same ones will not help. */
    INVALID_ARGUMENT,
    /**
     * Something the plugin depends on is not answering — the mail server, the object store, the
     * network. Retryable, and the one kind a circuit breaker is counting.
     */
    UNAVAILABLE,
    /**
     * The plugin's own credentials were refused by whatever it talks to. Not the core's capability
     * check, which never reaches the plugin: that call is refused before it is made.
     */
    DENIED,
    /** The call could not be finished in the time the caller said it would wait. */
    DEADLINE_EXCEEDED,
    /** Anything else. Not retryable, because nothing here promises that a repeat is safe. */
    INTERNAL
  }
}
