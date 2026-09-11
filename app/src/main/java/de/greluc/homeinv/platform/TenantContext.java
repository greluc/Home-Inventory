/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.platform;

import java.util.Optional;
import java.util.UUID;

/**
 * Holds the tenant the current thread is acting for.
 *
 * <p>The value comes from the authenticated principal and from nowhere else. It is never read from
 * a request parameter, a header or a path segment — an attacker controls all three, and the whole
 * point of the row-level security policies in {@code 07 §7.5} is that they hold when the
 * application layer is wrong. A tenant id that a client can choose would make the second line of
 * defence agree with the first one's mistake.
 *
 * <p>The context is deliberately not an {@code Optional} field with a setter. It is set once when a
 * request's identity is established and cleared when that request ends, and
 * {@link #runAs(UUID, Runnable)} is the only way to establish it, so there is no path that sets it
 * and forgets to clear it. A thread returning to the pool with a tenant still attached would serve
 * the next request as the wrong tenant — the failure this class exists to make impossible.
 */
public final class TenantContext {

  private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

  private TenantContext() {}

  /**
   * Returns the tenant of the current thread, or empty when none is established.
   *
   * <p>Empty is a legitimate state, not an error: authentication happens before any tenant is
   * known, because the credential presented is an e-mail address and an e-mail address does not
   * name a tenant. Callers that require a tenant use {@link #require()} and get a clear failure
   * instead of a confusing one further down.
   *
   * @return the current tenant, or empty outside a tenant-scoped unit of work
   */
  public static Optional<UUID> current() {
    return Optional.ofNullable(CURRENT.get());
  }

  /**
   * Returns the tenant of the current thread, failing when none is established.
   *
   * @return the current tenant, never {@code null}
   * @throws IllegalStateException when called outside a tenant-scoped unit of work. This is a
   *     programming error rather than a client error: the call reached code that assumes a tenant
   *     without passing through the filter that establishes one.
   */
  public static UUID require() {
    UUID tenant = CURRENT.get();
    if (tenant == null) {
      throw new IllegalStateException(
          "No tenant context. This code path assumes a tenant but was reached without one — "
              + "check that the request passes through the authentication filter.");
    }
    return tenant;
  }

  /**
   * Runs {@code body} with {@code tenantId} established, and restores the previous value afterwards.
   *
   * <p>The previous value is restored rather than cleared, so a nested call — a scheduled job that
   * processes several tenants in turn, an administrative operation that steps into one tenant and
   * back — cannot leave the outer scope holding the inner tenant.
   *
   * @param tenantId the tenant to act for; must not be {@code null}
   * @param body the work to run
   */
  public static void runAs(UUID tenantId, Runnable body) {
    UUID previous = CURRENT.get();
    CURRENT.set(java.util.Objects.requireNonNull(tenantId, "tenantId"));
    try {
      body.run();
    } finally {
      if (previous == null) {
        CURRENT.remove();
      } else {
        CURRENT.set(previous);
      }
    }
  }

  /**
   * Runs {@code body} with {@code tenantId} established and returns its result.
   *
   * <p>The value-returning twin of {@link #runAs}, with the same restore-rather-than-clear
   * behaviour, so a nested scope cannot leave the outer one holding the inner tenant.
   *
   * @param tenantId the tenant to act for; must not be {@code null}
   * @param body the work to run
   * @param <T> what the body produces
   * @return the result of {@code body}
   */
  public static <T> T callAs(UUID tenantId, java.util.function.Supplier<T> body) {
    UUID previous = CURRENT.get();
    CURRENT.set(java.util.Objects.requireNonNull(tenantId, "tenantId"));
    try {
      return body.get();
    } finally {
      if (previous == null) {
        CURRENT.remove();
      } else {
        CURRENT.set(previous);
      }
    }
  }

  /**
   * Clears the tenant of the current thread.
   *
   * <p>Called when a request ends. A thread handing back to the pool with a tenant still set would
   * serve the next request as that tenant if anything downstream forgot to establish its own.
   */
  public static void clear() {
    CURRENT.remove();
  }
}
