/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tenancy.application;

import de.greluc.homeinv.tenancy.api.RevocationUnusableException;
import de.greluc.homeinv.tenancy.domain.Tenant;
import de.greluc.homeinv.tenancy.infrastructure.TenantRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional half of withdrawing an erasure request.
 *
 * <p>A separate bean rather than a method on {@link DefaultTenantLifecycle}, for the reason
 * {@code TenantBootstrap} and {@code InvitationRedemption} exist: a {@code @Transactional} method
 * invoked from inside its own class does not go through the Spring proxy, so the annotation has no
 * effect and the work runs with no transaction at all — silently.
 *
 * <p>The tenant context must already be established when this is called, and the caller establishes
 * it from the token rather than from anything the requester said.
 */
@Component
@Slf4j
@RequiredArgsConstructor
class TenantRevocation {

  private final TenantRepository tenants;
  private final Clock clock;

  /**
   * Makes the tenant active again, if the request is still open and still within its grace period.
   *
   * @param tenantId the tenant the token named
   * @param grace how long a request waits before it is carried out
   * @return the same id, for the caller to answer with
   * @throws RevocationUnusableException when the request is gone or the period is over
   */
  @Transactional
  UUID revoke(UUID tenantId, Duration grace) {
    Tenant tenant = tenants.findById(tenantId).orElseThrow(RevocationUnusableException::new);

    if (!tenant.isPendingDeletion()) {
      // Already withdrawn, or already erased. Answered exactly like a token that
      // never existed: to whoever holds the link the three are one condition.
      throw new RevocationUnusableException();
    }
    if (tenant.getDeletionRequestedAt().plus(grace).isBefore(Instant.now(clock))) {
      // The period is over and the erasure is under way or done. Withdrawing now
      // would restore a tenant whose data is already going.
      throw new RevocationUnusableException();
    }

    tenant.revokeDeletion(Instant.now(clock));
    log.warn("The erasure of tenant {} was withdrawn; it is active again.", tenantId);
    return tenantId;
  }
}
