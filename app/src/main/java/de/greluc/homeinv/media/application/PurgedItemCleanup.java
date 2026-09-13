/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.media.application;

import de.greluc.homeinv.inventory.api.ItemPurged;
import de.greluc.homeinv.media.api.MediaService;
import de.greluc.homeinv.media.domain.Attachment;
import de.greluc.homeinv.media.infrastructure.AttachmentRepository;
import de.greluc.homeinv.platform.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Detaches everything that hung on an item that has been purged (REQ-CORE-009).
 *
 * <h2>Why a listener and not a foreign key</h2>
 *
 * <p>{@code media.attachment} is polymorphic — one table for what hangs on an item and on a place —
 * so it cannot carry a foreign key into either (07 §7.8). Nothing in the database therefore removes
 * an attachment when the item under it goes, and a purge would leave a row pointing at an id nothing
 * answers for.
 *
 * <h2>Why it runs in the same transaction</h2>
 *
 * <p>{@link Propagation#MANDATORY} and a plain {@link EventListener}, not an asynchronous one: the
 * detaching has to commit with the removal or not at all. Deferred, a failure here would leave the
 * item gone and its attachments behind, which is the exact state this exists to prevent.
 *
 * <p>Detaching rather than deleting the blobs: {@link MediaService#detach} already decides what
 * happens to an object that loses its last reference, and duplicating that decision here would make
 * two places responsible for a tenant's storage counting.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class PurgedItemCleanup {

  /** How many attachments are read at a time; the loop below repeats until none is left. */
  private static final int PAGE = 200;

  private final AttachmentRepository attachments;
  private final MediaService media;

  /**
   * Detaches every live attachment of the item that was purged.
   *
   * @param event the purge, carrying the tenant and the item
   */
  @EventListener
  @Transactional(propagation = Propagation.MANDATORY)
  public void onItemPurged(ItemPurged event) {
    TenantContext.runAs(
        event.tenantId(),
        () -> {
          // A page at a time, until none is left. Every read in this application
          // is bounded, and this one has to reach ALL of them: an attachment the
          // loop stopped short of would point at an id nothing answers for, which
          // is the state this listener exists to prevent. Each pass detaches what
          // it read, so the next page is a different set.
          int detached = 0;
          while (true) {
            var hanging =
                attachments.findLiveFor(
                    event.tenantId(),
                    "ITEM",
                    event.itemId(),
                    org.springframework.data.domain.PageRequest.of(0, PAGE));
            if (hanging.isEmpty()) {
              break;
            }
            for (Attachment attachment : hanging) {
              media.detach(attachment.getMediaObjectId(), "ITEM", event.itemId(), null);
            }
            detached += hanging.size();
          }
          if (detached > 0) {
            log.info("Detached {} attachment(s) from purged item {}", detached, event.itemId());
          }
        });
  }
}
