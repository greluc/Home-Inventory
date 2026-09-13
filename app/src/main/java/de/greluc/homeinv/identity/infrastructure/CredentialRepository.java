/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.identity.infrastructure;

import de.greluc.homeinv.identity.domain.Credential;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence for second factors.
 *
 * <p>No tenant filter, because the table is instance-wide (07 §7.1): the second factor is asked for
 * between the password and the session, when no tenant is known. Every query here takes a user id,
 * and the only user id any caller can supply is the one the login has just authenticated or the one
 * in the caller's own session.
 */
public interface CredentialRepository extends JpaRepository<Credential, UUID> {

  /**
   * The account's TOTP secret, confirmed or not.
   *
   * @param userId the account
   * @return the live TOTP credential, or empty
   */
  @Query("select c from Credential c where c.userId = :userId and c.kind = 'TOTP'"
      + " and c.deletedAt is null")
  Optional<Credential> findTotp(@Param("userId") UUID userId);

  /**
   * The account's unspent recovery codes.
   *
   * <p>Every one of them, because a code is checked by hashing the candidate against each: an
   * Argon2id hash cannot be looked up, which is the property that makes it worth storing.
   *
   * @param userId the account
   * @return the live, unspent codes
   */
  @Query("select c from Credential c where c.userId = :userId and c.kind = 'RECOVERY_CODE'"
      + " and c.lastUsedAt is null and c.deletedAt is null")
  List<Credential> findUnspentRecoveryCodes(@Param("userId") UUID userId);

  /**
   * The account's passkeys, oldest first.
   *
   * @param userId the account
   * @return the live passkeys
   */
  @Query("select c from Credential c where c.userId = :userId and c.kind = 'PASSKEY'"
      + " and c.deletedAt is null order by c.createdAt asc")
  List<Credential> findPasskeys(@Param("userId") UUID userId);

  /**
   * One passkey by the credential id an assertion presents.
   *
   * <p>Scoped to the account as well as to the id: an assertion says which credential answered, and
   * this is a second factor, so whose login it is answering is already known. A lookup by id alone
   * would be the passwordless flow, which this is not (REQ-AUTH-002).
   *
   * @param userId the account
   * @param credentialId base64url of the credential id
   * @return the passkey, or empty
   */
  @Query("select c from Credential c where c.userId = :userId and c.kind = 'PASSKEY'"
      + " and c.credentialId = :credentialId and c.deletedAt is null")
  Optional<Credential> findPasskey(
      @Param("userId") UUID userId, @Param("credentialId") String credentialId);

  /**
   * Every live recovery code, spent or not, for reissuing a set.
   *
   * @param userId the account
   * @return the live codes
   */
  @Query("select c from Credential c where c.userId = :userId and c.kind = 'RECOVERY_CODE'"
      + " and c.deletedAt is null")
  List<Credential> findRecoveryCodes(@Param("userId") UUID userId);
}
