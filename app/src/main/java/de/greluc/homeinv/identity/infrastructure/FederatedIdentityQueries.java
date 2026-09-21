/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.identity.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The links that last: {@code identity.federated_identity}.
 *
 * <p>An account is found here by {@code (issuer, subject)} and never by an address. People change
 * addresses and providers reuse them; the pair is what a provider promises not to.
 */
@Repository
@RequiredArgsConstructor
public class FederatedIdentityQueries {

  private final JdbcClient jdbc;

  /**
   * The account linked to a foreign identity.
   *
   * @param issuer the {@code iss} of the verified token
   * @param subject its {@code sub}
   * @return the account, or empty when nobody has linked it
   */
  @Transactional(readOnly = true)
  public Optional<UUID> accountFor(String issuer, String subject) {
    return jdbc.sql(
            """
            select user_id from identity.federated_identity
             where issuer = ? and subject = ?
            """)
        .params(issuer, subject)
        .query(UUID.class)
        .optional();
  }

  /**
   * Attaches a foreign identity to an account.
   *
   * @param userId the account
   * @param providerKey the configuration it was linked through
   * @param issuer the {@code iss} of the verified token
   * @param subject its {@code sub}
   * @param email what the address was at linking, or null — evidence, never an identifier
   * @return {@code true} when the link was made, {@code false} when that identity already belongs
   *     to an account. The unique index is what decides, rather than a read before the write: two
   *     links racing would both find nothing and both insert
   */
  @Transactional
  public boolean link(
      UUID userId, String providerKey, String issuer, String subject, String email) {
    try {
      jdbc.sql(
              """
              insert into identity.federated_identity
                  (user_id, provider_key, issuer, subject, linked_email)
              values (?::uuid, ?, ?, ?, ?)
              """)
          .params(userId.toString(), providerKey, issuer, subject, email)
          .update();
      return true;
    } catch (DuplicateKeyException alreadyLinked) {
      return false;
    }
  }

  /**
   * Records that a link signed somebody in.
   *
   * @param issuer the {@code iss}
   * @param subject its {@code sub}
   * @param now the moment
   */
  @Transactional
  public void used(String issuer, String subject, Instant now) {
    jdbc.sql(
            """
            update identity.federated_identity set last_used_at = ?
             where issuer = ? and subject = ?
            """)
        .params(java.sql.Timestamp.from(now), issuer, subject)
        .update();
  }

  /**
   * What one account has linked, for its own account page.
   *
   * @param userId whose
   * @return the links, newest first
   */
  @Transactional(readOnly = true)
  public List<Link> of(UUID userId) {
    return jdbc.sql(
            """
            select id, provider_key, issuer, subject, linked_email, linked_at, last_used_at
              from identity.federated_identity
             where user_id = ?::uuid
             order by linked_at desc
            """)
        .param(userId.toString())
        .query(
            (rs, row) ->
                new Link(
                    UUID.fromString(rs.getString("id")),
                    rs.getString("provider_key"),
                    rs.getString("issuer"),
                    rs.getString("subject"),
                    rs.getString("linked_email"),
                    rs.getTimestamp("linked_at").toInstant(),
                    rs.getTimestamp("last_used_at") == null
                        ? null
                        : rs.getTimestamp("last_used_at").toInstant()))
        .list();
  }

  /**
   * Removes one of an account's links.
   *
   * <p>Scoped to the account in the statement rather than checked before it: a delete that trusted
   * a caller's id would remove somebody else's link on a guessed one.
   *
   * @param userId whose
   * @param id which link
   * @return {@code true} when a link was removed
   */
  @Transactional
  public boolean unlink(UUID userId, UUID id) {
    return jdbc.sql(
            "delete from identity.federated_identity where id = ?::uuid and user_id = ?::uuid")
        .params(id.toString(), userId.toString())
        .update()
        > 0;
  }

  /**
   * One link, as an account page shows it.
   *
   * @param id the link
   * @param providerKey which configuration it was made through
   * @param issuer the provider that said so
   * @param subject its own id for the person, shown because it is what the link IS
   * @param email the address at linking, or null
   * @param linkedAt when it was made
   * @param lastUsedAt when it last signed somebody in, or null
   */
  public record Link(
      UUID id,
      String providerKey,
      String issuer,
      String subject,
      String email,
      Instant linkedAt,
      Instant lastUsedAt) {}
}
