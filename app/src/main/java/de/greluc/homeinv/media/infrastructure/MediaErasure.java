/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.media.infrastructure;

import de.greluc.homeinv.platform.TenantErasure;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Removes this block's share of a tenant (REQ-TEN-011).
 *
 * <p>Attachments and derivatives before the objects they describe.
 *
 * <p>The <b>blobs</b> are not removed here. They live in the blob store behind a port and are
 * reclaimed by the orphan sweep of 13 §13.8, which already removes what no reference points at —
 * with a grace period, never immediately. Deleting them inside this transaction would mean a
 * rollback leaving rows that point at bytes that are gone, which is the one direction this must
 * not fail in.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class MediaErasure implements TenantErasure {

  /**
   * The deletions, in the order a foreign key allows.
   *
   * <p>Children before parents. The database would refuse the other order and say so, but a refusal
   * mid-erasure leaves a tenant half gone, and the order is cheaper to get right here than to
   * recover from there.
   *
   * <p>Each statement is written out whole rather than a verb joined to a table name. The rule that
   * keeps SQL out of string concatenation admits no exception for a constant (REQ-SEC-031), because
   * the reader after next is the one who joins a parameter to it.
   */
  private static final List<String> DELETES =
      List.of(
          "delete from media.attachment",
          "delete from media.media_variant",
          "delete from media.media_object");

  private final JdbcClient jdbc;

  /**
   * {@inheritDoc}
   *
   * <p>First, and nothing forces it: {@code media.attachment} names its target by kind and id
   * rather than by a foreign key, because a polymorphic reference cannot be one (V9). Going first
   * means no row anywhere points at a media object that is still there to be read.
   */
  @Override
  public int order() {
    return 10;
  }

  @Override
  public String block() {
    return "media";
  }

  /**
   * {@inheritDoc}
   *
   * <p>Its own transaction, and not the runner's. {@code app.tenant_id} is published when a
   * transaction begins, so a delete outside one runs with no tenant set and the policy removes
   * nothing — and a block that failed would otherwise roll back every block before it.
   */
  @Override
  @Transactional
  public BlockReport erase(UUID tenantId) {
    long removed = 0;
    for (String statement : DELETES) {
      // No tenant predicate: the context is established and the policy scopes
      // the delete. A predicate here would be a second place the tenant is
      // decided, and the one that is wrong is always the second.
      removed += jdbc.sql(statement).update();
    }
    log.info("Erased {} rows of block {} for tenant {}", removed, block(), tenantId);
    return new BlockReport(block(), removed, null);
  }
}
