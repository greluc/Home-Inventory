/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.identity.domain;

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
 * A token that belongs to a tenant and acts inside it (REQ-AUTH-010).
 *
 * <p>Tenant-scoped, unlike {@link AppUser} and {@link Credential} beside it: a tenant administrator
 * creates one, it acts in that tenant and nowhere else, and it goes when the tenant does. What
 * makes it an {@code identity} notion rather than a {@code tenancy} one is what it is — a way of
 * authenticating (04 §4.3).
 *
 * <p>It holds a role rather than a set of permissions, which is what makes a machine and a person
 * answerable by the same {@code AccessControl}: the pair here is the pair a membership carries.
 */
@Entity
@Table(schema = "identity", name = "service_account")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ServiceAccount {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false, updatable = false)
  private UUID tenantId;

  /** What it is called, so a list of tokens is a list somebody can act on. */
  @Column(name = "name", nullable = false)
  private String name;

  /** What it is for, or null. */
  @Column(name = "description")
  private String description;

  /** One of the six built-in roles. */
  @Column(name = "role", nullable = false)
  private String role;

  /** A tenant-owned role extending it (REQ-TEN-006), or null. */
  @Column(name = "role_definition_id")
  private UUID roleDefinitionId;

  /**
   * The SHA-256 of the token, and never the token.
   *
   * <p>Shown once at creation and unreadable afterwards (REQ-AUTH-010, REQ-SEC-048). Not exposed
   * through the class-level {@code @Getter} for {@code AppUser.passwordHash}'s reason.
   */
  @Getter(AccessLevel.NONE)
  @Column(name = "token_hash", nullable = false, updatable = false)
  private String tokenHash;

  /** When it stops working. Required: a machine credential nobody reviews is one nobody revokes. */
  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  /** When it last authenticated, so an unused token can be recognised as one. */
  @Column(name = "last_used_at")
  private Instant lastUsedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "created_by", updatable = false)
  private UUID createdBy;

  @Column(name = "updated_by")
  private UUID updatedBy;

  /** When it was revoked. A tombstone: what a machine could do, and until when, is auditable. */
  @Column(name = "deleted_at")
  private Instant deletedAt;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  /**
   * Issues a service account.
   *
   * @param tenantId the tenant it acts in
   * @param name what it is called
   * @param description what it is for, or null
   * @param role the built-in role it holds
   * @param roleDefinitionId a tenant-owned role extending it, or null
   * @param tokenHash the SHA-256 of the token, which the caller has just shown once
   * @param expiresAt when it stops working
   * @param actor who created it
   * @param now the creation instant
   * @return the new service account
   */
  public static ServiceAccount issue(
      UUID tenantId,
      String name,
      String description,
      String role,
      UUID roleDefinitionId,
      String tokenHash,
      Instant expiresAt,
      UUID actor,
      Instant now) {
    ServiceAccount account = new ServiceAccount();
    account.id = UUID.randomUUID();
    account.tenantId = tenantId;
    account.name = name;
    account.description = description;
    account.role = role;
    account.roleDefinitionId = roleDefinitionId;
    account.tokenHash = tokenHash;
    account.expiresAt = expiresAt;
    account.createdAt = now;
    account.updatedAt = now;
    account.createdBy = actor;
    account.updatedBy = actor;
    return account;
  }

  /**
   * Records that the token authenticated.
   *
   * @param now when
   */
  public void used(Instant now) {
    this.lastUsedAt = now;
    this.updatedAt = now;
  }

  /**
   * Revokes it, leaving the tombstone.
   *
   * @param actor who revoked it
   * @param now when
   */
  public void revoke(UUID actor, Instant now) {
    this.deletedAt = now;
    this.updatedAt = now;
    this.updatedBy = actor;
  }

  /**
   * Whether the token still works.
   *
   * @param now the moment to judge it at
   * @return true when it is neither revoked nor expired
   */
  public boolean isUsable(Instant now) {
    return deletedAt == null && expiresAt.isAfter(now);
  }
}
