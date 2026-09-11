/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tenancy.infrastructure;

import de.greluc.homeinv.tenancy.domain.Tenant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for tenants.
 *
 * <p>No tenant filter on the query methods, and none is needed: the row-level security policy on
 * this table compares its own id against the context, so a tenant can only ever see itself.
 */
public interface TenantRepository extends JpaRepository<Tenant, UUID> {}
