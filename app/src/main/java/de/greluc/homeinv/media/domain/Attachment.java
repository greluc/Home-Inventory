/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.media.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * That a file hangs on an item or a location.
 *
 * <p>Polymorphic by {@code targetKind} and {@code targetId} rather than two tables: an attachment is
 * the same thing either way, and two tables would mean two of every query. The price is no foreign
 * key to the target - which would have to name another block's schema anyway, and no block may do
 * that (04 s4.5).
 */
@Entity
@Table(schema = "media", name = "attachment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Attachment {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false, updatable = false)
  private UUID tenantId;

  @Column(name = "media_object_id", nullable = false, updatable = false)
  private UUID mediaObjectId;

  /** {@code ITEM} or {@code LOCATION}. */
  @Column(name = "target_kind", nullable = false, updatable = false)
  private String targetKind;

  @Column(name = "target_id", nullable = false, updatable = false)
  private UUID targetId;

  /** The image a list shows. At most one per target, enforced by a partial unique index. */
  @Column(name = "primary_image", nullable = false)
  private boolean primaryImage;

  @Column(name = "display_order", nullable = false)
  private int displayOrder;

  /**
   * What this attachment is for (REQ-LIFE-016).
   *
   * <p>On the attachment rather than on the file: the same scan of a receipt may be the purchase
   * proof of one item and an ordinary document on another, and the file is stored once by content
   * address either way (ADR-0032).
   */
  @Column(name = "role", nullable = false)
  private String role;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "created_by", updatable = false)
  private UUID createdBy;

  @Column(name = "updated_by")
  private UUID updatedBy;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  private Attachment(UUID id, UUID tenantId, UUID mediaObjectId, String targetKind, UUID targetId,
      boolean primaryImage, UUID actor, Instant now) {
    this.id = id;
    this.tenantId = tenantId;
    this.mediaObjectId = mediaObjectId;
    this.targetKind = targetKind;
    this.targetId = targetId;
    this.primaryImage = primaryImage;
    this.displayOrder = 0;
    this.createdAt = now;
    this.updatedAt = now;
    this.createdBy = actor;
    this.updatedBy = actor;
  }

  /**
   * Attaches a file to a thing.
   *
   * @param id the identifier
   * @param tenantId the owning tenant
   * @param mediaObjectId the file
   * @param targetKind {@code ITEM} or {@code LOCATION}
   * @param targetId what it hangs on
   * @param primaryImage whether it becomes the image lists show
   * @param actor who attached it
   * @param now when
   * @return the new attachment
   */
  public static Attachment create(UUID id, UUID tenantId, UUID mediaObjectId, String targetKind,
      UUID targetId, boolean primaryImage, String role, UUID actor, Instant now) {
    if (!"ITEM".equals(targetKind) && !"LOCATION".equals(targetKind)) {
      throw new IllegalArgumentException("A target is an ITEM or a LOCATION");
    }
    Attachment attachment =
        new Attachment(id, tenantId, mediaObjectId, targetKind, targetId, primaryImage, actor, now);
    // Null is `PHOTO`, which is what every attachment written before the column
    // existed was. A role the database does not allow is refused here rather
    // than by a constraint three layers down, so the message names the field.
    String chosen = role == null || role.isBlank() ? "PHOTO" : role;
    if (!ROLES.contains(chosen)) {
      throw new IllegalArgumentException("An attachment's role is one of " + ROLES);
    }
    attachment.role = chosen;
    return attachment;
  }

  /** What an attachment may be for; the same four the database allows. */
  private static final java.util.Set<String> ROLES =
      java.util.Set.of("PHOTO", "RECEIPT", "WARRANTY_PROOF", "OTHER");

  /**
   * Detaches, leaving a tombstone.
   *
   * @param actor who detached it
   * @param now when
   */
  public void markDeleted(UUID actor, Instant now) {
    if (this.deletedAt != null) {
      return;
    }
    this.deletedAt = now;
    // Cleared on the way out: the partial unique index only covers live rows, so
    // a tombstone that still claims to be primary would be harmless in the
    // database and confusing in a query that forgets the predicate.
    this.primaryImage = false;
    this.updatedBy = actor;
    this.updatedAt = now;
  }
}
