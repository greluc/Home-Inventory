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

  /**
   * The tenant-owned role extending {@link #role}, or null (REQ-TEN-006).
   *
   * <p>The built-in name above stays meaningful either way: a tenant-owned role EXTENDS one of the
   * six, so there is always an answer to "what is this person at least" — and it is what they fall
   * back to if the definition is later removed.
   */
  @Column(name = "role_definition_id")
  private UUID roleDefinitionId;

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

  /**
   * Puts this person in a different role (REQ-TEN-005).
   *
   * <p>Whether the actor may is decided before this is called, in the application layer, against
   * the ladder — the entity knows what a role is called and nothing about what one is worth.
   *
   * @param newRole one of the six built-in role names
   * @param newRoleDefinitionId the tenant-owned role extending it (REQ-TEN-006), or null
   * @param actor who is making the change
   * @param now the instant of the change
   */
  public void changeRole(String newRole, UUID newRoleDefinitionId, UUID actor, Instant now) {
    this.role = newRole;
    this.roleDefinitionId = newRoleDefinitionId;
    this.updatedBy = actor;
    this.updatedAt = now;
  }

  /**
   * Removes this person from the tenant.
   *
   * <p>A tombstone rather than a deletion (07 §7.1, rule 5): audit entries name the person, and a
   * row that vanished would leave them pointing at nothing. The partial unique index on
   * {@code (tenant_id, user_id)} excludes tombstones, so the same person can be invited back.
   *
   * @param actor who is removing them
   * @param now the instant of the removal
   */
  public void remove(UUID actor, Instant now) {
    this.deletedAt = now;
    this.updatedBy = actor;
    this.updatedAt = now;
  }
}
