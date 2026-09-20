/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.media.infrastructure;

import de.greluc.homeinv.inventory.api.ItemEvidence;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * What {@code media} can say about an item, for the insurance report (REQ-LIFE-016).
 *
 * <p>The inversion of {@link ItemEvidence}: {@code inventory} declares what it needs and this
 * answers, so nothing in {@code media} is pointed at by the block it already points at.
 *
 * <p>The order is the order a reader wants and is decided here rather than by the caller, because
 * it is a fact about attachments: the primary photograph, then the receipts, then the warranty
 * proofs, then the rest.
 */
@Component
@RequiredArgsConstructor
public class AttachedEvidence implements ItemEvidence {

  /**
   * Everything cleared and attached to any of these items.
   *
   * <p>{@code scan_state = 'CLEAN'} is the whole of the safety here: an attachment still waiting
   * for a verdict, or one the scanner refused, is not something to put in a document somebody files
   * with an insurer (REQ-MED-013).
   */
  private static final String EVIDENCE =
      """
      select a.target_id as item_id, o.id as media_object_id, o.media_type, o.byte_size,
             a.role, a.primary_image, o.sha256
      from media.attachment a
      join media.media_object o on o.tenant_id = a.tenant_id and o.id = a.media_object_id
      where a.target_kind = 'ITEM'
        and a.target_id = any(?::uuid[])
        and a.deleted_at is null
        and o.deleted_at is null
        and o.scan_state = 'CLEAN'
      order by a.target_id,
               a.primary_image desc,
               case a.role
                 when 'RECEIPT' then 1
                 when 'WARRANTY_PROOF' then 2
                 when 'PHOTO' then 3
                 else 4
               end,
               a.display_order,
               a.created_at
      """;

  private final JdbcClient jdbc;

  @Override
  @Transactional(readOnly = true)
  public Map<UUID, List<Attached>> forItems(Collection<UUID> itemIds) {
    if (itemIds.isEmpty()) {
      return Map.of();
    }
    Map<UUID, List<Attached>> byItem = new LinkedHashMap<>();
    jdbc.sql(EVIDENCE)
        // As text with a cast, not as a UUID array: the driver has no mapping
        // from a Java UUID array to a Postgres one and binds something that
        // matches nothing at all -- no error, no rows.
        .param(itemIds.stream().map(UUID::toString).toArray(String[]::new))
        .query(
            (rs, rowNum) ->
                byItem
                    .computeIfAbsent(rs.getObject("item_id", UUID.class), any -> new ArrayList<>())
                    .add(
                        new Attached(
                            rs.getObject("media_object_id", UUID.class),
                            rs.getString("media_type"),
                            rs.getLong("byte_size"),
                            rs.getString("role"),
                            rs.getBoolean("primary_image"),
                            rs.getString("sha256"))))
        .list();
    return Map.copyOf(byItem);
  }
}
