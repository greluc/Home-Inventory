/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tenancy.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * That a person belongs to a tenant, and in what role.
 *
 * <p>Tenant data, and therefore RLS-protected - which also makes it the guard on the instance-wide
 * user table: a user is reachable only through a membership of the caller's own tenant.
 *
 * <p>Stage 0 writes {@code OWNER} only. The remaining built-in roles arrive with REQ-TEN-005 and
 * need no schema change, which is why the check constraint already lists all six.
 */
@Entity
@Table(schema = "tenancy", name = "membership")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Membership {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false, updatable = false)
  private UUID tenantId;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  /** One of OWNER, ADMIN, MEMBER, CONTRIBUTOR, VIEWER, GUEST. */
  @Column(name = "role", nullable = false)
  private String role;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "created_by", updatable = false)
  private UUID createdBy;

  @Column(name = "updated_by")
  private UUID updatedBy;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  private Membership(UUID id, UUID tenantId, UUID userId, String role, Instant now) {
    this.id = id;
    this.tenantId = tenantId;
    this.userId = userId;
    this.role = role;
    this.createdAt = now;
    this.updatedAt = now;
  }

  /**
   * Creates a membership.
   *
   * @param id the identifier
   * @param tenantId the tenant
   * @param userId the person
   * @param role one of the six built-in roles
   * @param now the creation instant
   * @return the new membership, not yet persisted
   */
  public static Membership create(UUID id, UUID tenantId, UUID userId, String role, Instant now) {
    return new Membership(id, tenantId, userId, role, now);
  }
}
