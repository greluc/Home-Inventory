/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.catalog.api;

import de.greluc.homeinv.platform.EventType;
import de.greluc.homeinv.platform.TenantScopedEvent;
import java.util.UUID;

/**
 * A field became searchable, sortable or facetable — or stopped being one of them.
 *
 * <p>The event 07 §7.3 names as the trigger for re-projecting {@code item_attr_index}: the side
 * table mirrors exactly the fields carrying one of those three flags, so changing a flag makes every
 * item of that type wrong until it is re-projected. No DDL, no lock, and cancellable — which is why
 * this is an event and not part of the write that caused it.
 *
 * @param tenantId whose type system changed
 * @param versionId the version the field belongs to
 * @param fieldId the definition
 * @param key the attribute key to re-project under
 * @param projected whether the field is mirrored from now on; false means its rows are removed
 */
public record FieldSearchabilityChanged(
    UUID tenantId, UUID versionId, UUID fieldId, String key, boolean projected) implements TenantScopedEvent {

  /**
   * What happened.
   *
   * @return type.field-searchability-changed
   */
  @Override
  public EventType eventType() {
    return EventType.TYPE_FIELD_SEARCHABILITY_CHANGED;
  }

  /**
   * What it happened to.
   *
   * @return the type version whose field changed, which is what a reader would go and read
   */
  @Override
  public UUID subjectId() {
    return versionId;
  }
}
