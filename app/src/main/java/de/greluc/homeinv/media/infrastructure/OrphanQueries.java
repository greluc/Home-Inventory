/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.media.infrastructure;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the orphaned blob sweep reads and removes (REQ-MED-011).
 *
 * <p>Two reads with different reach. Finding the tenants spans them, so it goes through the {@code
 * SECURITY DEFINER} function of {@code V62} — the sweep has no tenant context because it is looking
 * for the ones that need one, and {@code homeinv_app} has neither {@code BYPASSRLS} nor any way to
 * enumerate tenants. Everything after that runs inside one tenant's context, under the ordinary
 * policy.
 */
@Component
@RequiredArgsConstructor
public class OrphanQueries {

  private final JdbcClient jdbc;

  /**
   * Which tenants hold a blob whose grace period has elapsed.
   *
   * @param before the moment a blob must have been unreferenced since
   * @return the tenants, which is usually none
   */
  @Transactional(readOnly = true)
  public List<UUID> tenantsWithOrphans(Instant before) {
    return jdbc
        .sql("select tenant_id from media.tenants_with_orphaned_blobs(?)")
        .param(java.sql.Timestamp.from(before))
        .query((ResultSet rs, int row) -> rs.getObject(1, UUID.class))
        .list();
  }

  /**
   * The blobs of the tenant in context whose grace period has elapsed.
   *
   * <p>Ordered oldest first, so a large clear-out removes the longest-dead blobs first and a
   * bounded batch still makes progress in the right direction.
   *
   * @param before the moment a blob must have been unreferenced since
   * @param limit how many at most
   * @return the orphans
   */
  @Transactional(readOnly = true)
  public List<Orphan> orphansOf(Instant before, int limit) {
    return jdbc
        .sql(
            """
            select id, sha256
            from media.media_object
            where tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid
              and ref_count = 0
              and unreferenced_since <= ?
            order by unreferenced_since
            limit ?
            """)
        .param(java.sql.Timestamp.from(before))
        .param(limit)
        .query((ResultSet rs, int row) -> new Orphan(rs.getObject("id", UUID.class), rs.getString("sha256")))
        .list();
  }

  /**
   * Removes the row once its bytes have gone.
   *
   * <p>Scoped by reference count as well as by id: a reference that arrived between the read and
   * this write means somebody attached the blob again, and the row must stay. The bytes are already
   * gone in that case, which is why the sweep is ordered to make that the recoverable failure.
   *
   * @param tenantId whose
   * @param id the media object
   * @return how many rows went, which is one or none
   */
  @Transactional
  public int forget(UUID tenantId, UUID id) {
    return jdbc
        .sql("delete from media.media_object where tenant_id = ? and id = ? and ref_count = 0")
        .param(tenantId)
        .param(id)
        .update();
  }

  /**
   * One blob nothing points at.
   *
   * @param id the media object
   * @param sha256 its content address, which is what the store knows it by
   */
  public record Orphan(UUID id, String sha256) {}
}
