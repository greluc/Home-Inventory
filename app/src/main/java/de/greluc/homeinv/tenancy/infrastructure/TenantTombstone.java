/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tenancy.infrastructure;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The last write of an erasure: the tenant row becomes a tombstone (REQ-TEN-011).
 *
 * <h2>Why the run does this and no block does</h2>
 *
 * <p>{@code tenancy.tenants_due_for_erasure} skips a tombstoned row, so this mark is what ends the
 * erasure — and a mark set before the blocks had finished would end it while rows were still there,
 * with nothing due any more to come back for them. It is therefore the last statement of the run
 * and not the last statement of the {@code tenancy} block, which has to go earlier because a
 * membership references a location and a role definition.
 *
 * <h2>What the row keeps</h2>
 *
 * <p>The id, the name, and who asked and when. The certificate names the tenant, and a certificate
 * pointing at an id nothing answers for would be evidence of an erasure that cannot say what was
 * erased. The revocation token goes: a request that has been carried out cannot be withdrawn, and a
 * token that outlived its own erasure would be a link that still answers.
 */
@Component
@RequiredArgsConstructor
public class TenantTombstone {

  /**
   * Marks the tenant erased.
   *
   * <p>{@code where deleted_at is null} rather than an id: the tenant context is set and the policy
   * scopes the update to the one row, so a predicate here would be a second place the tenant is
   * decided — and it makes the statement idempotent, which a resumed run needs.
   */
  private static final String TOMBSTONE =
      "update tenancy.tenant set lifecycle_state = 'ERASED', deleted_at = now(),"
          + " updated_at = now(), revocation_token_hash = null, version = version + 1"
          + " where deleted_at is null";

  private final JdbcClient jdbc;

  /**
   * Tombstones the tenant the context names.
   *
   * @param tenantId the tenant being erased, for the caller's log — the context decides which row
   *     is written
   * @return whether this call was the one that marked it, false when a previous run already had
   */
  @Transactional
  public boolean tombstone(UUID tenantId) {
    return jdbc.sql(TOMBSTONE).update() > 0;
  }
}
