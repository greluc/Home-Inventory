/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tagging.api;

import de.greluc.homeinv.platform.EventType;
import de.greluc.homeinv.platform.TenantScopedEvent;
import java.util.UUID;
import org.springframework.modulith.events.Externalized;

/**
 * A tag was taken off an item or a place.
 *
 * <p>Also raised for each tag an exclusive group displaces: assigning "used" where "new" was is one
 * assignment and one unassignment, and a consumer that saw only the first would believe a thing
 * carried both.
 *
 * @param tenantId whose data changed
 * @param tagId the tag
 * @param target what kind of thing it came off
 * @param targetId the item or place
 */
@Externalized("homeinv.tagging::tag-unassigned.v1")
public record TagUnassigned(
    UUID tenantId, UUID tagId, TagService.TagTarget target, UUID targetId) implements TenantScopedEvent {

  /**
   * What happened.
   *
   * @return tag.unassigned
   */
  @Override
  public EventType eventType() {
    return EventType.TAG_UNASSIGNED;
  }

  /**
   * What it happened to.
   *
   * @return the thing that no longer wears the tag, not the tag
   */
  @Override
  public UUID subjectId() {
    return targetId;
  }
}
