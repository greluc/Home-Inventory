/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.identity.infrastructure;

import de.greluc.homeinv.identity.api.FederatedSignIn;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The sign-in in flight: {@code identity.federated_login}.
 *
 * <p>Instance-wide and without row-level security, because a flow begins before there is a session
 * and, for somebody who has no account here, before there is a tenant even in principle (07 §7.1).
 * What bounds it is the handle: every read is by its SHA-256, and no endpoint takes a user id.
 */
@Repository
@RequiredArgsConstructor
public class FederatedLoginQueries {

  private final JdbcClient jdbc;

  /**
   * Records a flow that is about to send a browser away.
   *
   * @param handleHash the SHA-256 of the handle, which is all that is stored
   * @param providerKey which provider
   * @param purpose what the flow may do when it comes back
   * @param userId the account a link attaches to, or null for a sign-in
   * @param codeVerifier the PKCE verifier this deployment keeps
   * @param nonce the core's one-time value for the token
   * @param redirectUri the redirect the provider is given and will check again
   * @param returnTo where to send the browser afterwards, or null
   * @param expiresAt when the handle stops working
   */
  @Transactional
  public void start(
      String handleHash,
      String providerKey,
      FederatedSignIn.Purpose purpose,
      UUID userId,
      String codeVerifier,
      String nonce,
      String redirectUri,
      String returnTo,
      Instant expiresAt) {
    jdbc.sql(
            """
            insert into identity.federated_login
                (handle_hash, provider_key, purpose, user_id, code_verifier, nonce,
                 redirect_uri, return_to, expires_at)
            values (?, ?, ?, ?::uuid, ?, ?, ?, ?, ?)
            """)
        .params(
            handleHash,
            providerKey,
            purpose.name(),
            userId == null ? null : userId.toString(),
            codeVerifier,
            nonce,
            redirectUri,
            returnTo,
            java.sql.Timestamp.from(expiresAt))
        .update();
  }

  /**
   * Spends a flow and returns what it was for.
   *
   * <p>One statement, and the {@code where} clause is the whole single-use guarantee: two callbacks
   * arriving together both try to write {@code consumed_at}, one updates a row and the other
   * updates none. A read followed by a write would let both through, which is the replay
   * REQ-AUTH-005 is verified against.
   *
   * @param handleHash the SHA-256 of the handle the callback presented
   * @param now the moment, so that expiry is decided against one clock
   * @return the flow, or empty when it is unknown, expired or already spent
   */
  @Transactional
  public Optional<Flow> consume(String handleHash, Instant now) {
    return jdbc.sql(
            """
            update identity.federated_login
               set consumed_at = ?
             where handle_hash = ?
               and consumed_at is null
               and expires_at > ?
            returning provider_key, purpose, user_id, code_verifier, nonce, redirect_uri,
                      return_to
            """)
        .params(java.sql.Timestamp.from(now), handleHash, java.sql.Timestamp.from(now))
        .query(
            (rs, row) ->
                new Flow(
                    rs.getString("provider_key"),
                    FederatedSignIn.Purpose.valueOf(rs.getString("purpose")),
                    rs.getString("user_id") == null
                        ? null
                        : UUID.fromString(rs.getString("user_id")),
                    rs.getString("code_verifier"),
                    rs.getString("nonce"),
                    rs.getString("redirect_uri"),
                    rs.getString("return_to")))
        .optional();
  }

  /**
   * Removes flows that will never be finished.
   *
   * @param before the moment everything expired earlier than is gone
   * @return how many rows were removed
   */
  @Transactional
  public int forgetExpired(Instant before) {
    return jdbc.sql("delete from identity.federated_login where expires_at < ?")
        .param(java.sql.Timestamp.from(before))
        .update();
  }

  /**
   * One sign-in in flight, as the callback needs it.
   *
   * @param providerKey which provider it went to
   * @param purpose what it may do
   * @param userId the account a link attaches to, or null
   * @param codeVerifier the PKCE verifier to present with the code
   * @param nonce the value the plugin checks inside the token
   * @param redirectUri the redirect to repeat, which the provider compares
   * @param returnTo where the browser was going, or null
   */
  public record Flow(
      String providerKey,
      FederatedSignIn.Purpose purpose,
      UUID userId,
      String codeVerifier,
      String nonce,
      String redirectUri,
      String returnTo) {}
}
