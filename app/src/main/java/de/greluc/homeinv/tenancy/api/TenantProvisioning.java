/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tenancy.api;

import java.util.UUID;

/**
 * Creating a tenant and everything it needs to be usable.
 *
 * <p>Published because the one-shot {@code bootstrap} service calls it, and a block reaches another
 * only through its {@code api} package (REQ-NFR-021). Stage 1 adds tenant administration, which
 * calls the same port from an endpoint rather than from a runner.
 */
public interface TenantProvisioning {

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
  UUID provision(String name, UUID ownerUserId);
}
