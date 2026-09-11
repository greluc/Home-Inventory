/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tenancy.application;

import de.greluc.homeinv.platform.TenantContext;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Creates a tenant and everything it needs to be usable.
 *
 * <h2>The bootstrap, and why it needs no special privilege</h2>
 *
 * <p>{@code tenancy.tenant} carries a policy with {@code WITH CHECK (tenant_id = …)}, and its
 * {@code tenant_id} is generated from its own {@code id}. Inserting the first row of a tenant
 * therefore requires the tenant context to already be that tenant — before the row exists. That
 * looks like the same circularity the login has, and it is not: the id is ours to choose. Generating
 * it first and opening the transaction inside {@code runAs(newId, …)} closes the loop without a
 * {@code SECURITY DEFINER} function and without {@code BYPASSRLS}.
 *
 * <p>The order matters and is not interchangeable. The context is established <em>outside</em> the
 * transaction, because {@code TenantAwareTransactionManager} reads it in {@code doBegin} — setting
 * it inside would set it after the connection had already been configured for no tenant. The write
 * itself lives in {@link TenantBootstrap} rather than in a method here, because a {@code
 * @Transactional} method called from its own class is not proxied and would silently run without a
 * transaction at all.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TenantProvisioningService {

  private final TenantBootstrap bootstrap;

  /**
   * Creates a tenant, makes a user its owner, and seeds the catalog.
   *
   * <p>All of it in one transaction. A tenant without its built-in type cannot hold a single item,
   * because {@code inventory.item.item_type_version_id} is {@code NOT NULL} — a half-provisioned
   * tenant is not a degraded tenant, it is a broken one.
   *
   * @param name the tenant's display name
   * @param ownerUserId the user who becomes {@code OWNER}
   * @return the new tenant's id
   */
  public UUID provision(String name, UUID ownerUserId) {
    UUID tenantId = UUID.randomUUID();
    // Established before the transaction opens: the transaction manager publishes
    // whatever is set at doBegin, and a context set inside would arrive too late.
    TenantContext.runAs(tenantId, () -> bootstrap.write(tenantId, name, ownerUserId));
    return tenantId;
  }

}
