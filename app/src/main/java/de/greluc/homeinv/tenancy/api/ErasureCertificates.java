/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tenancy.api;

import de.greluc.homeinv.platform.TenantErasure;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The evidence that tenants were erased (REQ-TEN-011, REQ-PRIV-005).
 *
 * <p>Read by the instance operator, and by nobody else: the table is instance-wide (07 §7.1) and
 * the application exposes no tenant-scoped path to it. A tenant that has been erased has no members
 * left to ask.
 */
public interface ErasureCertificates {

  /**
   * One certificate.
   *
   * @param tenantId the tenant that was erased
   * @param tenantName what it was called, as it stood when the erasure began
   * @param requestedAt when the erasure was asked for
   * @param requestedBy which account asked. An opaque id and nothing more, which is what 05 §5.9
   *     means by keeping the record "pseudonymised"
   * @param completedAt when the last block reported
   * @param report one entry per building block: how many rows it removed, and where something was
   *     deliberately left, why
   */
  record Certificate(
      UUID tenantId,
      String tenantName,
      Instant requestedAt,
      UUID requestedBy,
      Instant completedAt,
      List<TenantErasure.BlockReport> report) {}

  /**
   * One page of certificates, newest first.
   *
   * @param items the certificates
   * @param nextCursor where the next page starts, or null when this was the last
   */
  record CertificatePage(List<Certificate> items, String nextCursor) {}

  /**
   * The certificate for one tenant.
   *
   * @param tenantId the tenant that was erased
   * @return its certificate, or empty when that tenant was never erased
   */
  Optional<Certificate> forTenant(UUID tenantId);

  /**
   * One page of certificates, newest first.
   *
   * @param cursor an opaque cursor from a previous page, or null for the first
   * @param limit how many at most, capped at 200
   * @return the page
   */
  CertificatePage certificates(String cursor, int limit);
}
