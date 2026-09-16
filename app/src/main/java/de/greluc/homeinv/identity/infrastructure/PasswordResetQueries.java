/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.identity.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every statement the password reset runs (REQ-SEC-018).
 *
 * <p>No tenant appears in any of them, and that is the table's whole shape: a reset is asked for at
 * the login page, where there is no session and therefore no tenant, and for an address nobody has
 * an account for there is none even in principle (07 §7.1).
 *
 * <p>The token itself is never stored and never queried — only its SHA-256 (REQ-SEC-048). A caller
 * presents the token, this class hashes it and looks the hash up, so a database dump hands nobody a
 * working reset link.
 */
@Component
@RequiredArgsConstructor
public class PasswordResetQueries {

  private final JdbcClient jdbc;

  /**
   * Opens a reset, replacing whatever was open for this account.
   *
   * <p>One live token per account: two would be two ways in, and somebody clicking the older mail
   * should be told it no longer works rather than let in. The replacement is a delete and an
   * insert in one transaction rather than an update, so the row's identity and its creation time
   * belong to the request that actually made it.
   *
   * @param userId whose account
   * @param tokenHash the SHA-256 of the token that was minted
   * @param expiresAt when it stops working
   * @param requestedIp where the request came from, for the message and for an operator reading a
   *     run of them
   * @return the new row's id
   */
  @Transactional
  public UUID open(UUID userId, String tokenHash, Instant expiresAt, String requestedIp) {
    jdbc.sql("delete from identity.password_reset where user_id = ? and used_at is null")
        .param(userId)
        .update();
    return jdbc
        .sql(
            """
            insert into identity.password_reset (user_id, token_hash, expires_at, requested_ip)
            values (?, ?, ?, cast(? as inet))
            returning id
            """)
        .param(userId)
        .param(tokenHash)
        .param(java.sql.Timestamp.from(expiresAt))
        .param(requestedIp)
        .query(UUID.class)
        .single();
  }

  /**
   * The open, unexpired reset for a token hash, if there is one.
   *
   * <p>Expiry and spending are both part of the query rather than of the caller: a row that is past
   * its time or already used simply is not found, so there is one answer for all three cases and
   * nothing to forget to check.
   *
   * @param tokenHash the SHA-256 of what was presented
   * @param now the present
   * @return the reset, or empty when the token is unknown, expired or spent
   */
  @Transactional(readOnly = true)
  public Optional<OpenReset> find(String tokenHash, Instant now) {
    return jdbc
        .sql(
            """
            select id, user_id, expires_at
            from identity.password_reset
            where token_hash = ? and used_at is null and expires_at > ?
            """)
        .param(tokenHash)
        .param(java.sql.Timestamp.from(now))
        .query(
            (ResultSet rs, int row) ->
                new OpenReset(
                    rs.getObject("id", UUID.class),
                    rs.getObject("user_id", UUID.class),
                    rs.getTimestamp("expires_at").toInstant()))
        .optional();
  }

  /**
   * Spends a token.
   *
   * <p>Conditional on it still being unspent, so two redemptions racing each other cannot both
   * succeed: the second updates no row and the caller sees that it lost.
   *
   * @param id the reset
   * @return whether this call was the one that spent it
   */
  @Transactional
  public boolean spend(UUID id) {
    return jdbc
            .sql("update identity.password_reset set used_at = now() where id = ? and used_at is null")
            .param(id)
            .update()
        == 1;
  }

  /**
   * One open reset.
   *
   * @param id the row
   * @param userId whose account
   * @param expiresAt when it stops working
   */
  public record OpenReset(UUID id, UUID userId, Instant expiresAt) {}
}
