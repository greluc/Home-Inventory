/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.idempotency.infrastructure;

import de.greluc.homeinv.idempotency.api.IdempotencyKeyConflictException;
import de.greluc.homeinv.idempotency.api.IdempotentRequests;
import de.greluc.homeinv.platform.TenantContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code idempotency.processed_request}, read and written inside the caller's transaction.
 *
 * <p>{@link Propagation#MANDATORY} on both halves, and that is the design rather than caution: the
 * whole point of REQ-API-005 as ADR-0009 settled it is that the record and the entity share one
 * {@code COMMIT}. A call that arrived with no transaction of its own would write the record in one
 * of its own, which is the two-store problem the decision rejects — so it fails loudly here instead
 * of quietly there.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ProcessedRequests implements IdempotentRequests {

  private final JdbcClient jdbc;

  @Override
  @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
  public Optional<String> replay(String operation, String key, String requestHash) {
    return jdbc
        .sql(
            """
            select operation, request_hash, response::text as response
            from idempotency.processed_request
            where tenant_id = ? and key = ?
              and created_at >= now() - interval '24 hours'
            """)
        .params(TenantContext.require(), key)
        .query(
            (rs, rowNum) -> {
              // Constant-time on the hash. It is not a secret and nothing here is
              // authenticating, so this is not closing a hole -- it is refusing to
              // teach the next reader that comparing two hashes with `equals` is
              // fine, which in the place where it matters it is not.
              boolean sameRequest =
                  MessageDigest.isEqual(
                      requestHash.getBytes(StandardCharsets.UTF_8),
                      rs.getString("request_hash").getBytes(StandardCharsets.UTF_8));
              if (!operation.equals(rs.getString("operation")) || !sameRequest) {
                throw new IdempotencyKeyConflictException();
              }
              return rs.getString("response");
            })
        .optional();
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void remember(
      String operation, String key, String requestHash, String response, UUID actor) {

    // Before the insert, not after, and that ordering is load-bearing: `replay`
    // has just treated an expired row as absent, so the key may still be occupied
    // by one -- and the primary key would refuse the row rather than replace it.
    // Sweeping first is also where the sweep belongs at all, because here is
    // where a tenant context exists.
    expireForThisTenant();

    jdbc.sql(
            """
            insert into idempotency.processed_request
                (tenant_id, key, request_hash, operation, response, created_by)
            values (?, ?, ?, ?, ?::jsonb, ?)
            """)
        .params(TenantContext.require(), key, requestHash, operation, response, actor)
        .update();
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public int expireForThisTenant() {
    UUID tenantId = TenantContext.require();
    int removed =
        jdbc.sql(
                """
                delete from idempotency.processed_request
                where tenant_id = ? and created_at < now() - interval '24 hours'
                """)
            .param(tenantId)
            .update();
    if (removed > 0) {
      log.debug("Expired {} idempotency record(s) for tenant {}.", removed, tenantId);
    }
    return removed;
  }
}
