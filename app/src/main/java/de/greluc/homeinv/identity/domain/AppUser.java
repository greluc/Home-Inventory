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
 * A person who can log in.
 *
 * <p>Instance-wide, not tenant-scoped: authentication presents an e-mail address, and an e-mail
 * address does not name a tenant, so a {@code tenant_id} here would have to be read before the row
 * carrying it has been found. It is the fifth entry on the closed list in {@code 07 §7.1}, and what
 * protects it instead is that nothing reaches it except through a membership of the caller's own
 * tenant.
 *
 * <p>The password hash is on the entity rather than in a separate credential table. Splitting it
 * would suggest a user can have several credentials, which is a stage-1 question (a second factor,
 * OIDC) and not one stage 0 should answer by accident.
 */
@Entity
@Table(schema = "identity", name = "app_user")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AppUser {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  /**
   * The address the user logs in with.
   *
   * <p>Stored as entered so it can be shown back that way, and compared lowercased — the unique
   * index is on {@code lower(email)}. Treating {@code Lucas@…} and {@code lucas@…} as two accounts
   * is a way to lock somebody out of their own.
   */
  @Column(name = "email", nullable = false)
  private String email;

  /** The name shown in the UI and in audit entries. */
  @Column(name = "display_name", nullable = false)
  private String displayName;

  /** The UI language. One of the two shipped (REQ-NFR-033). */
  @Column(name = "locale", nullable = false)
  private String locale;

  /**
   * The Argon2id hash, in PHC string format.
   *
   * <p>The format carries the parameters, so a login can notice that a hash was made with a cost
   * below the current setting and rehash it — which is the only way a raised cost ever reaches
   * existing accounts (REQ-SEC-010).
   *
   * <p>Never logged, never returned by any endpoint, and deliberately not exposed through a getter
   * Lombok would have generated for the whole class: see {@link #passwordHash()}.
   */
  @Getter(AccessLevel.NONE) // The class-level @Getter would expose it; see passwordHash().
  @Column(name = "password_hash", nullable = false)
  private String passwordHash;

  @Column(name = "password_changed_at", nullable = false)
  private Instant passwordChangedAt;

  /**
   * When the account was locked, or null.
   *
   * <p>Distinct from {@code deletedAt}: a locked account still exists, its memberships still
   * resolve, and its audit entries still name a person who is there.
   */
  @Column(name = "locked_at")
  private Instant lockedAt;

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

  private AppUser(
      UUID id, String email, String displayName, String locale, String passwordHash, Instant now) {
    this.id = id;
    this.email = email;
    this.displayName = displayName;
    this.locale = locale;
    this.passwordHash = passwordHash;
    this.passwordChangedAt = now;
    this.createdAt = now;
    this.updatedAt = now;
  }

  /**
   * Creates a user.
   *
   * @param id the identifier
   * @param email the login address, stored as given
   * @param displayName the name shown in the UI; must not be blank
   * @param locale the UI language
   * @param passwordHash an already-hashed password in PHC format. Hashing happens in the
   *     application service, not here: the domain must not depend on a password encoder, and a
   *     constructor taking a plaintext password is one somebody will call with a plaintext password
   *     that then reaches a log
   * @param now the creation instant
   * @return the new user, not yet persisted
   */
  public static AppUser create(
      UUID id, String email, String displayName, String locale, String passwordHash, Instant now) {
    if (email == null || email.isBlank()) {
      throw new IllegalArgumentException("A user needs an e-mail address");
    }
    if (displayName == null || displayName.isBlank()) {
      throw new IllegalArgumentException("A user needs a display name");
    }
    return new AppUser(id, email, displayName, locale, passwordHash, now);
  }

  /**
   * Returns the stored hash for verification.
   *
   * <p>Named as a method rather than exposed by a generated getter so every use is visible in a
   * search for {@code passwordHash()}. There are exactly two legitimate callers: the login check
   * and the rehash after a raised cost.
   *
   * @return the Argon2id hash in PHC format
   */
  public String passwordHash() {
    return passwordHash;
  }

  /**
   * Replaces the stored hash, after a password change or a rehash at a raised cost.
   *
   * @param newHash the new hash in PHC format
   * @param now the instant of the change
   */
  public void replacePasswordHash(String newHash, Instant now) {
    this.passwordHash = newHash;
    this.passwordChangedAt = now;
    this.updatedAt = now;
  }

  /**
   * Whether this account may authenticate at all.
   *
   * @return {@code true} when the account is neither locked nor deleted
   */
  public boolean canAuthenticate() {
    return lockedAt == null && deletedAt == null;
  }
}
