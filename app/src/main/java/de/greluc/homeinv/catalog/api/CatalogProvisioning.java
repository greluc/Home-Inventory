/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.catalog.api;

import java.util.UUID;

/**
 * What {@code tenancy} may ask {@code catalog} to do when a tenant is created.
 *
 * <p>A port rather than direct access to the catalog's tables. Tenant provisioning needs a tenant
 * to end up with a usable type and a set of location categories; how those are shaped is the
 * catalog's business, and a provisioning service that wrote catalog rows itself would be a second
 * place that has to know the shape (REQ-NFR-020, REQ-NFR-023).
 */
public interface CatalogProvisioning {

  /**
   * Creates the built-in type and the shipped location categories for a new tenant.
   *
   * <p>Runs inside the caller's transaction, so a tenant is never left half provisioned — a tenant
   * without its built-in type could not hold a single item, because
   * {@code inventory.item.item_type_version_id} is {@code NOT NULL}.
   *
   * @param tenantId the tenant being created
   * @param actor the user creating it, recorded in the audit columns
   * @return the id of the built-in type's first version, which every item of this tenant references
   *     at stage 0
   */
  UUID provisionDefaults(UUID tenantId, UUID actor);

  /**
   * The built-in type version every item of a tenant references at stage 0.
   *
   * <p>Exists because stage 0 has no configurable type system (O27): the client does not choose a
   * type, so something has to supply the reference the column requires.
   *
   * @param tenantId the tenant
   * @return the built-in type's version id
   * @throws IllegalStateException when the tenant has no built-in type, which means provisioning
   *     did not complete and no item can be created until it does
   */
  UUID builtinItemTypeVersion(UUID tenantId);
}
