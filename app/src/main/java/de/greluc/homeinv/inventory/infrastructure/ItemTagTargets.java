/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.infrastructure;

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
 * Whether an item is one this tenant can see, answered where the rows are.
 *
 * <p>{@code tagging} owns the assignment and may not read this schema (04 §4.5); it declares the
 * question in {@link TaggableTargets} and this answers it.
 *
 * <p>The condition is the one {@code ItemRepository.findLive} uses, and it has to stay that way: a
 * target the tag endpoints accept and the item endpoints deny would be a row somebody could tag and
 * then not find. {@code deleted_at is null} is what excludes an item in the trash, and row-level
 * security is what excludes another tenant's — so a foreign id and an unknown one are the same
 * answer here as everywhere else (REQ-SEC-025).
 */
@Component
@RequiredArgsConstructor
public class ItemTagTargets implements TaggableTargets {

  private final JdbcClient jdbc;

  /**
   * Items.
   *
   * @return {@link TagService.TagTarget#ITEM}
   */
  @Override
  public TagService.TagTarget kind() {
    return TagService.TagTarget.ITEM;
  }

  /**
   * Refuses an item this tenant has no live row for.
   *
   * @param targetId the item a caller named
   * @throws NotFoundException when it does not exist, is in the trash, or belongs to another tenant
   */
  @Override
  @Transactional(readOnly = true)
  public void requireVisible(UUID targetId) {
    boolean visible =
        jdbc.sql(
                """
                select exists (
                  select 1 from inventory.item
                  where tenant_id = ? and id = ? and deleted_at is null
                )
                """)
            .params(TenantContext.require(), targetId)
            .query(Boolean.class)
            .single();
    if (!visible) {
      throw new NotFoundException("item", targetId);
    }
  }
}
