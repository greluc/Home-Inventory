/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tenancy.api;

import java.util.UUID;

/**
 * Creating a tenant as a person, rather than as a deployment (REQ-TEN-002).
 *
 * <p>Distinct from {@link TenantProvisioning}, which is the mechanism and has no opinion about who
 * is allowed to use it. The one-shot {@code bootstrap} service calls that one: the very first
 * tenant of an instance is created before anybody could hold an entitlement, and a quota on it
 * would be a quota on the deployment itself. Everything a signed-in person does goes through here,
 * where the bound applies.
 */
public interface TenantService {

  /**
   * Creates a tenant and makes the caller its owner.
   *
   * <p>The entitlement itself is required by the endpoint
   * ({@link de.greluc.homeinv.authorization.api.Entitlement#CREATE_TENANT}); what this adds is the
   * bound ADR-0003 put beside it — "quotas exist from the start, because with open tenant creation
   * they are abuse protection and do not work as a retrofit".
   *
   * @param name the tenant's display name
   * @param ownerUserId the account that becomes {@code OWNER}
   * @return the new tenant's id
   * @throws TenantLimitReachedException when the account already owns as many as it may
   */
  UUID create(String name, UUID ownerUserId);
}
