/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tenancy.api;

import java.util.List;
import java.util.UUID;

/**
 * Setting what a tenant may use, from outside that tenant (REQ-TEN-009, 13 §13.9).
 *
 * <p>The instance operator's port, and the only one in this block that takes a tenant id. That is
 * not a hole in REQ-SEC-004: the caller is not acting *for* the tenant and holds no membership in
 * it. Every method goes through the {@code SECURITY DEFINER} functions of migration {@code V24},
 * which is what 07 §7.5 names for cross-tenant administration — "explicit, logged … never
 * BYPASSRLS" — and each reaches one table that holds no domain data.
 *
 * <p><b>Limits only.</b> What a tenant has <em>used</em> is its own data and is not readable here.
 * An operator who needs to see inside a tenant impersonates (REQ-SEC-072), which is an audited,
 * time-limited act the affected user can see.
 */
public interface QuotaAdministration {

  /**
   * One limit an operator has set.
   *
   * @param quota which bound
   * @param permitted how much is allowed
   */
  record QuotaLimit(QuotaGuard.Quota quota, long permitted) {}

  /**
   * The limits set for one tenant, which may be none.
   *
   * <p>A quota with no entry falls back to the instance-wide default, so an empty answer means
   * "nothing has been decided about this tenant" rather than "this tenant may do nothing".
   *
   * @param tenantId the tenant
   * @return the limits, by quota name
   */
  List<QuotaLimit> limitsOf(UUID tenantId);

  /**
   * Sets what a tenant may use.
   *
   * <p>Zero is legal and means "none for now": a way to stop a tenant growing without removing it.
   * There is no way to say "unlimited" — an unbounded quota is not a quota, and the way to raise one
   * is to raise it.
   *
   * @param tenantId the tenant
   * @param quota which bound
   * @param permitted how much to allow, zero or more
   * @param actor the operator making the change, recorded on the row and in the log
   */
  void setLimit(UUID tenantId, QuotaGuard.Quota quota, long permitted, UUID actor);
}
