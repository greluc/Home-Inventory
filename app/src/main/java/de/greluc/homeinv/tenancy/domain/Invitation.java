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
 * An invitation into a tenant: single-use, time-limited, bound to an address (REQ-TEN-004).
 *
 * <p>All three properties are state on this row rather than rules in a service, because an
 * invitation outlives the request that made it and the request that redeems it. The token itself is
 * never here — only its hash (REQ-SEC-048), so that a leaked database is not a set of working
 * invitations.
 */
@Entity
@Table(schema = "tenancy", name = "invitation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Invitation {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false, updatable = false)
  private UUID tenantId;

  /** The address this invitation is bound to, folded to lower case. */
  @Column(name = "email", nullable = false, updatable = false)
  private String email;

  /** One of OWNER, ADMIN, MEMBER, CONTRIBUTOR, VIEWER, GUEST. */
  @Column(name = "role", nullable = false, updatable = false)
  private String role;

  /**
   * The SHA-256 of the token, in lower-case hexadecimal.
   *
   * <p>A hash and not the token, so that reading this table yields no working invitation. SHA-256
   * and not a password hash: the token is 256 bits of randomness from a secure source, so there is
   * nothing to slow an attacker down about — the reason Argon2id exists is that people choose
   * passwords, and nobody chose this.
   */
  @Column(name = "token_hash", nullable = false, updatable = false)
  private String tokenHash;

  @Column(name = "expires_at", nullable = false, updatable = false)
  private Instant expiresAt;

  @Column(name = "accepted_at")
  private Instant acceptedAt;

  @Column(name = "accepted_by")
  private UUID acceptedBy;

  @Column(name = "revoked_at")
  private Instant revokedAt;

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

  private Invitation(
      UUID id,
      UUID tenantId,
      String email,
      String role,
      String tokenHash,
      Instant expiresAt,
      UUID actor,
      Instant now) {
    this.id = id;
    this.tenantId = tenantId;
    this.email = email;
    this.role = role;
    this.tokenHash = tokenHash;
    this.expiresAt = expiresAt;
    this.createdBy = actor;
    this.createdAt = now;
    this.updatedAt = now;
  }

  /**
   * Issues an invitation.
   *
   * @param id the identifier
   * @param tenantId the tenant being joined
   * @param email the address, already folded to lower case by the caller
   * @param role the role to grant on acceptance
   * @param tokenHash the SHA-256 of the token, hexadecimal
   * @param expiresAt when it stops working
   * @param actor who is inviting
   * @param now the issuing instant
   * @return the new invitation, not yet persisted
   */
  public static Invitation issue(
      UUID id,
      UUID tenantId,
      String email,
      String role,
      String tokenHash,
      Instant expiresAt,
      UUID actor,
      Instant now) {
    return new Invitation(id, tenantId, email, role, tokenHash, expiresAt, actor, now);
  }

  /**
   * Whether this invitation can still be redeemed.
   *
   * @param now the instant to judge it at
   * @return {@code true} when it is neither used, nor withdrawn, nor past its expiry
   */
  public boolean isUsable(Instant now) {
    return acceptedAt == null
        && revokedAt == null
        && deletedAt == null
        && expiresAt.isAfter(now);
  }

  /**
   * The state a client is shown.
   *
   * <p>Four values and not a boolean: an administrator looking at a list wants to know whether
   * somebody never came, declined to be re-invited, or was withdrawn. The caller <em>redeeming</em>
   * a token is told none of this (see {@code InvitationUnusableException}); this is for the tenant
   * that issued it.
   *
   * @param now the instant to judge it at
   * @return {@code ACCEPTED}, {@code REVOKED}, {@code EXPIRED} or {@code OPEN}
   */
  public String stateAt(Instant now) {
    if (acceptedAt != null) {
      return "ACCEPTED";
    }
    if (revokedAt != null) {
      return "REVOKED";
    }
    return expiresAt.isAfter(now) ? "OPEN" : "EXPIRED";
  }

  /**
   * Marks the invitation used.
   *
   * @param userId the account that redeemed it
   * @param now the instant of the acceptance
   */
  public void accept(UUID userId, Instant now) {
    this.acceptedAt = now;
    this.acceptedBy = userId;
    this.updatedBy = userId;
    this.updatedAt = now;
  }

  /**
   * Withdraws the invitation.
   *
   * @param actor who is withdrawing it
   * @param now the instant of the withdrawal
   */
  public void revoke(UUID actor, Instant now) {
    this.revokedAt = now;
    this.updatedBy = actor;
    this.updatedAt = now;
  }
}
