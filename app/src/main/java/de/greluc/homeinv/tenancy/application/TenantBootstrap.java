/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tenancy.application;

import de.greluc.homeinv.catalog.api.CatalogProvisioning;
import de.greluc.homeinv.tenancy.domain.Membership;
import de.greluc.homeinv.tenancy.domain.Tenant;
import de.greluc.homeinv.tenancy.infrastructure.MembershipRepository;
import de.greluc.homeinv.tenancy.infrastructure.TenantRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional half of tenant provisioning.
 *
 * <p>A separate bean rather than a method on {@link TenantProvisioningService}, for one unglamorous
 * reason: a {@code @Transactional} method invoked from inside its own class does not go through the
 * Spring proxy, so the annotation has no effect and the work runs with no transaction at all. The
 * failure is silent — everything appears to work until a partial provisioning is discovered by a
 * tenant that cannot create items.
 *
 * <p>The tenant context must already be established when this is called; see the class comment on
 * {@link TenantProvisioningService} for why it cannot be established here.
 */
@Component
@Slf4j
@RequiredArgsConstructor
class TenantBootstrap {

  private final TenantRepository tenants;
  private final MembershipRepository memberships;
  private final CatalogProvisioning catalog;
  private final Clock clock;

  /**
   * Writes the tenant, its owner's membership and the seeded catalog, in one transaction.
   *
   * <p>All or nothing. A tenant without its built-in type cannot hold a single item, because
   * {@code inventory.item.item_type_version_id} is {@code NOT NULL} — half a tenant is not a
   * degraded tenant, it is a broken one.
   *
   * @param tenantId the id chosen by the caller, already established as the tenant context
   * @param name the tenant's display name
   * @param ownerUserId the user who becomes {@code OWNER}
   */
  @Transactional
  void write(UUID tenantId, String name, UUID ownerUserId) {
    Instant now = Instant.now(clock);

    tenants.save(Tenant.create(tenantId, name, now));
    memberships.save(Membership.create(UUID.randomUUID(), tenantId, ownerUserId, "OWNER", now));
    catalog.provisionDefaults(tenantId, ownerUserId);

    log.info("Tenant {} provisioned with owner {}", tenantId, ownerUserId);
  }
}
