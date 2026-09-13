/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tenancy.infrastructure;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Which tenants the grace period has run out for (REQ-TEN-011).
 *
 * <p>A query that spans tenants, and {@code homeinv_app} can make none: it has neither
 * {@code BYPASSRLS} nor a way to enumerate tenants — 13 §13.8 makes the same point about the
 * catch-up scan, which travels on the broker for exactly this reason. This goes through
 * {@code tenancy.tenants_due_for_erasure}, the {@code SECURITY DEFINER} function of migration
 * {@code V31}, which is the way out 07 §7.5 sanctions.
 *
 * <p>It returns ids and instants. A caller learns which tenants are due and not one fact about any
 * of them; everything else the erasure needs is read inside each tenant's own context, under its
 * own policies.
 */
@Component
@RequiredArgsConstructor
public class TenantErasureQueries {

  private static final String DUE =
      "select tenant_id, requested_at from tenancy.tenants_due_for_erasure(?::interval)";

  private final JdbcClient jdbc;

  /**
   * A tenant whose erasure is due.
   *
   * @param tenantId the tenant
   * @param requestedAt when the erasure was asked for
   */
  public record DueTenant(UUID tenantId, Instant requestedAt) {}

  /**
   * Every tenant whose request is older than the grace period.
   *
   * @param grace how long a request waits
   * @return the tenants, oldest request first
   */
  public List<DueTenant> dueFor(Duration grace) {
    return jdbc
        .sql(DUE)
        .param(grace.toDays() + " days")
        .query(
            (rs, rowNum) ->
                new DueTenant(
                    rs.getObject("tenant_id", UUID.class),
                    rs.getObject("requested_at", java.time.OffsetDateTime.class).toInstant()))
        .list();
  }
}
