/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.locations.infrastructure;

import de.greluc.homeinv.platform.NotFoundException;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.tagging.api.TagService;
import de.greluc.homeinv.tagging.api.TaggableTargets;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Whether a place is one this tenant can see, answered where the rows are.
 *
 * <p>{@code tagging} owns the assignment and may not read this schema (04 §4.5); it declares the
 * question in {@link TaggableTargets} and this answers it.
 *
 * <p>The condition is the one {@code LocationRepository.findLive} uses, and it has to stay that way:
 * a place the tag endpoints accept and the location endpoints deny would be a row somebody could tag
 * and then not find. A session confined to a subtree is handled by the policy rather than here —
 * {@code app.location_scope} is set on the transaction and a place outside it returns no row
 * (REQ-TEN-007), which is the same answer a place of another tenant gets.
 */
@Component
@RequiredArgsConstructor
public class LocationTagTargets implements TaggableTargets {

  private final JdbcClient jdbc;

  /**
   * Places.
   *
   * @return {@link TagService.TagTarget#LOCATION}
   */
  @Override
  public TagService.TagTarget kind() {
    return TagService.TagTarget.LOCATION;
  }

  /**
   * Refuses a place this tenant has no live row for.
   *
   * @param targetId the place a caller named
   * @throws NotFoundException when it does not exist, has been removed, lies outside the subtree
   *     this session is confined to, or belongs to another tenant
   */
  @Override
  @Transactional(readOnly = true)
  public void requireVisible(UUID targetId) {
    boolean visible =
        jdbc.sql(
                """
                select exists (
                  select 1 from locations.location
                  where tenant_id = ? and id = ? and deleted_at is null
                )
                """)
            .params(TenantContext.require(), targetId)
            .query(Boolean.class)
            .single();
    if (!visible) {
      throw new NotFoundException("location", targetId);
    }
  }
}
