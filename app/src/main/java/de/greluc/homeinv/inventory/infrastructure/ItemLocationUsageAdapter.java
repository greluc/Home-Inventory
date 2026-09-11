/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.infrastructure;

import de.greluc.homeinv.inventory.api.ItemLocationUsage;
import de.greluc.homeinv.platform.TenantContext;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Answers {@link ItemLocationUsage} from the item table, inside the caller's tenant. */
@Component
@RequiredArgsConstructor
public class ItemLocationUsageAdapter implements ItemLocationUsage {

  private final ItemRepository items;

  @Override
  public boolean anyItemIn(UUID locationId) {
    return items.existsLiveInLocation(TenantContext.require(), locationId);
  }
}
