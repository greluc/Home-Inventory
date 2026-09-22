/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.platform;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ThreadLocalAccessor;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

/**
 * Who is calling and for which tenant, carried onto a thread the request did not start on.
 *
 * <h2>Why this exists</h2>
 *
 * <p>{@link CallerContext} and {@link TenantContext} are thread-locals, which is right: every
 * authorisation decision and every {@code SET LOCAL app.tenant_id} reads them, and passing both
 * through every signature in the system would be a parameter nobody could forget to pass and
 * everybody would.
 *
 * <p>A thread-local ends at the thread. That was true and invisible while every request was one
 * thread — and stopped being invisible with the GraphQL surface, whose {@code DataLoader}
 * dispatches run on an executor (REQ-API-006). There, {@code CallerContext.require()} threw <i>"no
 * caller is bound to this thread"</i> on a request that plainly had one.
 *
 * <p>{@code io.micrometer:context-propagation} is the mechanism Reactor, Spring for GraphQL and
 * Micrometer all already use for exactly this: a snapshot is taken where the context exists and
 * restored around the work that moved. Registering an accessor per thread-local is all it needs.
 *
 * <h2>What it deliberately does not do</h2>
 *
 * <p>It does not make either context <b>settable</b> from outside. The accessors below are nested
 * in this package on purpose, and the only public ways in are still {@link TenantContext#runAs} and
 * {@link CallerContext#runAs}, which restore what was there before. A public setter would be the
 * mechanism by which one tenant's work continues under another's id.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class ContextPropagation {

  /**
   * Registers both accessors with the one global registry.
   *
   * <p>In a constructor rather than a {@code @Bean}: {@link ContextRegistry#getInstance()} is a
   * singleton that Reactor and Spring for GraphQL read directly, not a bean anybody injects, so
   * what matters is that this has happened before the first request — which context refresh
   * guarantees.
   */
  public ContextPropagation() {
    ContextRegistry.getInstance()
        .registerThreadLocalAccessor(new TenantAccessor())
        .registerThreadLocalAccessor(new CallerAccessor());
    log.debug("The tenant and the caller now travel with the work rather than with the thread");
  }

  /** Carries {@link TenantContext}'s value. */
  static final class TenantAccessor implements ThreadLocalAccessor<UUID> {

    /** The key a snapshot stores it under. Namespaced, because the registry is global. */
    static final String KEY = "de.greluc.homeinv.tenant";

    @Override
    public Object key() {
      return KEY;
    }

    @Override
    public UUID getValue() {
      return TenantContext.current().orElse(null);
    }

    @Override
    public void setValue(UUID value) {
      TenantContext.bind(value);
    }

    @Override
    public void setValue() {
      TenantContext.clear();
    }
  }

  /** Carries {@link CallerContext}'s value. */
  static final class CallerAccessor implements ThreadLocalAccessor<CallerContext.Caller> {

    /** The key a snapshot stores it under. */
    static final String KEY = "de.greluc.homeinv.caller";

    @Override
    public Object key() {
      return KEY;
    }

    @Override
    public CallerContext.Caller getValue() {
      return CallerContext.current().orElse(null);
    }

    @Override
    public void setValue(CallerContext.Caller value) {
      CallerContext.bind(value);
    }

    @Override
    public void setValue() {
      CallerContext.clear();
    }
  }
}
