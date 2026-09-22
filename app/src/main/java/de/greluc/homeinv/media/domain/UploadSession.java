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
 * An upload that is still arriving (REQ-MED-008).
 *
 * <h2>What is here, and what deliberately is not</h2>
 *
 * <p>Not the bytes, and <b>not the offset</b>. The bytes are staged in the {@code blobstore}
 * service, which holds the volume so that {@code api} stays stateless (REQ-NFR-008) and so that a
 * second replica can continue an upload the first one began. The offset is the length of that
 * staged file, asked of the store on every request: a number kept in two places disagrees after a
 * crash, and the one on disk is the one that is true.
 *
 * <p>What is here is everything the store does not know — who the upload is for, what it will hang
 * on, how long it may sit unfinished — and the id, which is both the address of the staged bytes
 * and the last segment of the upload URL.
 *
 * <h2>Why the target is fixed at creation</h2>
 *
 * <p>A client that could change the target halfway through would have uploaded to one place and
 * attached to another, and the quota was claimed against the first.
 */
@Entity
@Table(schema = "media", name = "upload_session")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UploadSession {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false, updatable = false)
  private UUID tenantId;

  /** {@code ITEM} or {@code LOCATION}. */
  @Column(name = "target_kind", nullable = false, updatable = false)
  private String targetKind;

  @Column(name = "target_id", nullable = false, updatable = false)
  private UUID targetId;

  @Column(name = "primary_image", nullable = false, updatable = false)
  private boolean primaryImage;

  @Column(name = "role", nullable = false, updatable = false)
  private String role;

  /** What the client said it would send, checked against REQ-SEC-037 before a byte arrives. */
  @Column(name = "declared_length", nullable = false, updatable = false)
  private long declaredLength;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  /**
   * The file this upload became, once the last byte arrived and the pipeline ran.
   *
   * <p>Kept so that a client whose final response was lost asks again and is told the same media
   * id, rather than uploading the whole file a second time — which is the failure this feature
   * exists to prevent, arriving one request later than expected.
   */
  @Column(name = "media_object_id")
  private UUID mediaObjectId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "created_by", updatable = false)
  private UUID createdBy;

  @Column(name = "updated_by")
  private UUID updatedBy;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  /**
   * Begins an upload.
   *
   * @param id the upload's id, which is also where its bytes are staged
   * @param tenantId the owning tenant
   * @param targetKind {@code ITEM} or {@code LOCATION}
   * @param targetId what the finished file will hang on
   * @param primaryImage whether it becomes the image lists show
   * @param role what the attachment is for
   * @param declaredLength how many bytes the client says it will send
   * @param expiresAt when an unfinished upload stops being one worth keeping
   * @param actor who is uploading
   * @param now the moment
   * @return the session
   */
  public static UploadSession begin(
      UUID id,
      UUID tenantId,
      String targetKind,
      UUID targetId,
      boolean primaryImage,
      String role,
      long declaredLength,
      Instant expiresAt,
      UUID actor,
      Instant now) {
    UploadSession session = new UploadSession();
    session.id = id;
    session.tenantId = tenantId;
    session.targetKind = targetKind;
    session.targetId = targetId;
    session.primaryImage = primaryImage;
    session.role = role;
    session.declaredLength = declaredLength;
    session.expiresAt = expiresAt;
    session.createdAt = now;
    session.updatedAt = now;
    session.createdBy = actor;
    session.updatedBy = actor;
    return session;
  }

  /**
   * Records what the upload became.
   *
   * @param mediaObjectId the file it produced
   * @param actor who finished it
   * @param now the moment
   */
  public void completed(UUID mediaObjectId, UUID actor, Instant now) {
    this.mediaObjectId = mediaObjectId;
    this.updatedBy = actor;
    this.updatedAt = now;
  }

  /**
   * Whether this upload has already produced a file.
   *
   * @return true once the pipeline has run
   */
  public boolean isComplete() {
    return mediaObjectId != null;
  }
}
