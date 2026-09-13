/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.identity.infrastructure;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Finding a service account by its token, before any tenant is known (REQ-AUTH-010).
 *
 * <p>The circularity every token in this system has: the caller presents a token, the token decides
 * which tenant they act for, and with no {@code app.tenant_id} set the policy yields nothing. It
 * goes through {@code identity.service_account_by_token}, the {@code SECURITY DEFINER} function of
 * migration {@code V37} — the way out 07 §7.5 sanctions, and the fourth place this application uses
 * it after memberships, invitations and tenant revocations.
 *
 * <p>The function is deliberately unfiltered on expiry and revocation. Whether a token still works
 * is decided here, in one place, so that an expired token and one that never existed take the same
 * path — a query that filtered would answer the two at different speeds.
 */
@Component
@RequiredArgsConstructor
public class ServiceAccountTokens {

  private static final String BY_TOKEN =
      "select id, tenant_id, role, role_definition_id, expires_at, deleted_at"
          + " from identity.service_account_by_token(?)";

  private final JdbcClient jdbc;

  /**
   * What a token names, whether or not it still works.
   *
   * @param id the service account
   * @param tenantId the tenant it acts in
   * @param role the built-in role it holds
   * @param roleDefinitionId the tenant-owned role extending it, or null
   * @param expiresAt when it stops working
   * @param revokedAt when it was revoked, or null
   */
  public record TokenHolder(
      UUID id,
      UUID tenantId,
      String role,
      UUID roleDefinitionId,
      Instant expiresAt,
      Instant revokedAt) {

    /**
     * Whether the token may authenticate.
     *
     * @param now the moment to judge it at
     * @return true when it is neither revoked nor expired
     */
    public boolean isUsable(Instant now) {
      return revokedAt == null && expiresAt.isAfter(now);
    }
  }

  /**
   * The service account a token hash names.
   *
   * @param tokenHash the SHA-256 of the presented token
   * @return what it names, or empty when nothing does
   */
  @Transactional(readOnly = true)
  public Optional<TokenHolder> byToken(String tokenHash) {
    return jdbc
        .sql(BY_TOKEN)
        .param(tokenHash)
        .query(
            (rs, rowNum) ->
                new TokenHolder(
                    rs.getObject("id", UUID.class),
                    rs.getObject("tenant_id", UUID.class),
                    rs.getString("role"),
                    rs.getObject("role_definition_id", UUID.class),
                    rs.getObject("expires_at", java.time.OffsetDateTime.class).toInstant(),
                    rs.getObject("deleted_at", java.time.OffsetDateTime.class) == null
                        ? null
                        : rs.getObject("deleted_at", java.time.OffsetDateTime.class).toInstant()))
        .optional();
  }
}
