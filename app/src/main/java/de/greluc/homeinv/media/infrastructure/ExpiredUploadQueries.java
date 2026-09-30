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
 * Which tenants have an upload that timed out (REQ-MED-008).
 *
 * <p>Through the {@code SECURITY DEFINER} function of {@code V75}, for the reason every function on
 * 07 §7.5's list exists: the sweep has no tenant context because it is looking for the tenants that
 * need one, and {@code homeinv_app} has neither {@code BYPASSRLS} nor any way to enumerate tenants.
 * It returns tenant ids and nothing else; the sessions are read and removed inside each tenant's
 * own context, under the ordinary policy.
 */
@Component
@RequiredArgsConstructor
public class ExpiredUploadQueries {

  private final JdbcClient jdbc;

  /**
   * Which tenants hold an unfinished upload whose time is up.
   *
   * @param before the moment an upload must have expired before
   * @return the tenants, which is usually none
   */
  @Transactional(readOnly = true)
  public List<UUID> tenantsWithExpiredUploads(Instant before) {
    return jdbc
        .sql("select tenant_id from media.tenants_with_expired_uploads(?)")
        .param(java.sql.Timestamp.from(before))
        .query((ResultSet rs, int row) -> rs.getObject(1, UUID.class))
        .list();
  }
}
