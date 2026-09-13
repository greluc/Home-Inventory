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

  /**
   * The authenticated caller of the current request.
   *
   * <p>Both role components are strings and ids rather than anything richer, for the reason the
   * class comment gives: {@code platform} is the shared kernel and carries no domain logic, so what
   * a role <em>means</em> belongs to {@code authorization} and only there.
   *
   * @param userId the person
   * @param tenantId the tenant they are acting for, or null when they are in none
   * @param role the built-in role they hold there, or null when there is no tenant
   * @param roleDefinitionId the tenant-owned role extending it (REQ-TEN-006), or null
   * @param scopeLocationId the part of the location tree this membership is confined to
   *     (REQ-TEN-007), or null for the whole tenant. An id and not a path, because a path is a copy
   *     of something that moves — see {@code LocationScope}
   */
  public record Caller(
      UUID userId,
      UUID tenantId,
      String role,
      UUID roleDefinitionId,
      UUID scopeLocationId,
      java.time.Instant secondFactorAt) {

    /**
     * A caller whose second factor was proved at a known moment.
     *
     * <p>{@code secondFactorAt} is null for a session that never proved one — an account with no
     * authenticator, which {@code REQ-AUTH-003} allows only for roles that do not require one — and
     * for a caller built outside a web request. Both mean the same thing to the one rule that reads
     * it: a sensitive field is not shown (REQ-AUTH-011).
     *
     * @param userId the person
     * @param tenantId the tenant they are acting for
     * @param role the built-in role
     * @param roleDefinitionId the tenant-owned role extending it, or null
     * @param scopeLocationId the part of the tree they are confined to, or null
     */
    public Caller(
        UUID userId,
        UUID tenantId,
        String role,
        UUID roleDefinitionId,
        UUID scopeLocationId) {
      this(userId, tenantId, role, roleDefinitionId, scopeLocationId, null);
    }

    /**
     * A caller holding a plain built-in role over the whole tenant.
     *
     * @param userId the person
     * @param tenantId the tenant they are acting for
     * @param role the built-in role
     */
    public Caller(UUID userId, UUID tenantId, String role) {
      this(userId, tenantId, role, null, null);
    }

    /**
     * A caller whose role may be a tenant-owned one, over the whole tenant.
     *
     * @param userId the person
     * @param tenantId the tenant they are acting for
     * @param role the built-in role
     * @param roleDefinitionId the tenant-owned role extending it, or null
     */
    public Caller(UUID userId, UUID tenantId, String role, UUID roleDefinitionId) {
      this(userId, tenantId, role, roleDefinitionId, null);
    }
  }

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
