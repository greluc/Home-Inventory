/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.audit.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Who is acting, and from where, for the length of one request (REQ-SEC-068).
 *
 * <h2>Why this is ambient</h2>
 *
 * <p>An audit entry needs the actor, the address, the client and the correlation id. None of those
 * are things a domain service knows or should learn: an item service that took an IP address as a
 * parameter would carry the web layer into the domain, and one that took it from a request would be
 * a domain service that cannot be called from a queue.
 *
 * <p>So the boundary establishes it — the REST filter for a request, the worker for a job, the
 * plugin runtime for a plugin's call — and {@link AuditLog#record(String, String, UUID,
 * java.util.Map)} fills the six fields from here. A block writes what it knows: what happened, to
 * what, and what changed.
 *
 * <h2>The marker</h2>
 *
 * <p>{@link #recorded()} says that something already wrote an entry for this request. The REST
 * layer reads it after a successful mutating request and writes a plain entry when nothing did,
 * which is what makes "every mutating action" a property of the boundary rather than of everybody
 * remembering. A block that records its own — with a diff, which the boundary cannot know — sets it
 * by doing so.
 */
public final class AuditTrail {

  private static final ThreadLocal<Origin> CURRENT = new ThreadLocal<>();
  private static final ThreadLocal<Boolean> RECORDED = new ThreadLocal<>();

  private AuditTrail() {
    throw new AssertionError("Ambient state, not a thing to instantiate");
  }

  /**
   * Runs an action with this origin, and clears it afterwards.
   *
   * <p>Nested calls restore the previous origin rather than clearing, so a plugin call inside a
   * request does not leave the request without one.
   *
   * @param origin who is acting
   * @param action what to run
   */
  public static void runWith(Origin origin, Runnable action) {
    Origin previous = CURRENT.get();
    Boolean previouslyRecorded = RECORDED.get();
    CURRENT.set(origin);
    RECORDED.set(Boolean.FALSE);
    try {
      action.run();
    } finally {
      restore(previous, previouslyRecorded);
    }
  }

  /**
   * Runs something that returns a value with this origin.
   *
   * @param origin who is acting
   * @param action what to run
   * @param <T> what it returns
   * @return whatever it returned
   */
  public static <T> T callWith(Origin origin, java.util.function.Supplier<T> action) {
    Origin previous = CURRENT.get();
    Boolean previouslyRecorded = RECORDED.get();
    CURRENT.set(origin);
    RECORDED.set(Boolean.FALSE);
    try {
      return action.get();
    } finally {
      restore(previous, previouslyRecorded);
    }
  }

  private static void restore(Origin origin, Boolean recorded) {
    if (origin == null) {
      CURRENT.remove();
    } else {
      CURRENT.set(origin);
    }
    if (recorded == null) {
      RECORDED.remove();
    } else {
      RECORDED.set(recorded);
    }
  }

  /**
   * Sets the origin for whatever runs next on this thread, until {@link #clear()}.
   *
   * <p>The unscoped form, for a boundary that is not a block of code: a servlet interceptor sets it
   * before the handler and clears it after completion, which {@link #runWith} cannot express
   * because the two halves are different methods.
   *
   * @param origin who is acting
   */
  public static void establish(Origin origin) {
    CURRENT.set(origin);
    RECORDED.set(Boolean.FALSE);
  }

  /**
   * Forgets the origin.
   *
   * <p>On a pooled thread this is not tidiness: a thread that kept the last request's actor would
   * attribute the next one's actions to them.
   */
  public static void clear() {
    CURRENT.remove();
    RECORDED.remove();
  }

  /**
   * Who is acting now.
   *
   * @return the origin, or empty outside any established one
   */
  public static Optional<Origin> current() {
    return Optional.ofNullable(CURRENT.get());
  }

  /** Records that an entry has been written for whatever is running now. */
  public static void recorded() {
    RECORDED.set(Boolean.TRUE);
  }

  /**
   * Whether an entry has been written for whatever is running now.
   *
   * @return {@code true} when something already recorded one
   */
  public static boolean wasRecorded() {
    return Boolean.TRUE.equals(RECORDED.get());
  }

  /**
   * Who is acting and from where.
   *
   * @param actorKind what kind of thing is acting
   * @param actorId the person or service account, or {@code null} for a plugin or the system
   * @param actorLabel the plugin id or the task's name, or {@code null} for a person
   * @param ip the address the request came from, or {@code null} for a job
   * @param client what made the request — a user agent, a service account's name
   * @param correlationId the trace this belongs to, so an entry and the log lines around it can be
   *     put side by side
   */
  public record Origin(
      AuditLog.ActorKind actorKind,
      UUID actorId,
      String actorLabel,
      String ip,
      String client,
      String correlationId) {

    /**
     * A person acting through the API.
     *
     * @param actorId who
     * @param ip from where
     * @param client with what
     * @param correlationId in which trace
     * @return the origin
     */
    public static Origin ofUser(UUID actorId, String ip, String client, String correlationId) {
      return new Origin(AuditLog.ActorKind.USER, actorId, null, ip, client, correlationId);
    }

    /**
     * The system itself — a scheduled task, a startup routine.
     *
     * @param task what is running, as it is named in the operations documentation
     * @return the origin
     */
    public static Origin ofSystem(String task) {
      return new Origin(AuditLog.ActorKind.SYSTEM, null, task, null, task, null);
    }

    /**
     * A plugin, acting through the core's own API.
     *
     * @param pluginId which plugin
     * @param correlationId the trace of the call that led here
     * @return the origin
     */
    public static Origin ofPlugin(String pluginId, String correlationId) {
      return new Origin(AuditLog.ActorKind.PLUGIN, null, pluginId, null, pluginId, correlationId);
    }
  }
}
