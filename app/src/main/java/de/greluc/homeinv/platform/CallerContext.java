/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.platform;

import java.util.Optional;
import java.util.UUID;

/**
 * Who is making the current request.
 *
 * <h2>Why this is not {@link TenantContext}</h2>
 *
 * <p>They look alike and answer different questions. {@link TenantContext} is a <em>database
 * session property</em>: the value {@code TenantAwareTransactionManager} pushes into
 * {@code app.tenant_id} so that every row-level-security policy has something to compare against.
 * This one is an <em>identity</em>: which person, and in which role, so that the application layer
 * can answer "may they".
 *
 * <p>Merging them would mean one of two things, and both are worse. Either the database context
 * would carry a role it never uses — and a future reader would reasonably assume RLS consults it,
 * which it must not, because authorization lives in the application layer and only there
 * (ADR-0010) — or the identity would be set wherever the tenant is, including in the places that
 * set a tenant without a person: the login bootstrap, a migration, a future housekeeping job.
 *
 * <h2>The role is a string here</h2>
 *
 * <p>Deliberately. {@code platform} is the shared kernel and carries no domain logic (REQ-NFR-024);
 * the {@code Role} enum and everything that may be concluded from it belong to
 * {@code authorization}, which is the only block allowed to decide what a role means.
 */
public final class CallerContext {

  private static final ThreadLocal<Caller> CURRENT = new ThreadLocal<>();

  private CallerContext() {}

  /** The authenticated caller of the current request. */
  public record Caller(UUID userId, UUID tenantId, String role) {}

  /**
   * Runs an action with the caller published, and clears it afterwards.
   *
   * <p>The clearing is as much the point as the setting: threads are pooled — virtual threads less
   * so, but the pool is not the only way a thread is reused — and a caller left behind would be
   * inherited by whoever runs next. That failure grants one user's role to another, which is the
   * worst possible direction for it to fail in.
   *
   * @param caller who is calling
   * @param action what to run
   */
  public static void runAs(Caller caller, Runnable action) {
    Caller previous = CURRENT.get();
    CURRENT.set(caller);
    try {
      action.run();
    } finally {
      if (previous == null) {
        CURRENT.remove();
      } else {
        CURRENT.set(previous);
      }
    }
  }

  /**
   * The current caller, if there is one.
   *
   * @return the caller, or empty outside an authenticated request
   */
  public static Optional<Caller> current() {
    return Optional.ofNullable(CURRENT.get());
  }

  /**
   * The current caller, or a failure.
   *
   * @return the caller
   * @throws IllegalStateException when nothing authenticated this thread. It is a programming
   *     error, not a permission failure: an unauthenticated request never reaches code that asks.
   */
  public static Caller require() {
    return current()
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "No caller is bound to this thread. An authorization check ran outside an "
                        + "authenticated request, which means the filter chain was bypassed or the "
                        + "work moved to a thread the context was not propagated to."));
  }

  /** Removes the caller. Only the filter that set one should need this. */
  public static void clear() {
    CURRENT.remove();
  }
}
