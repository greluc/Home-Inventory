/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.media.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A stored blob and what is known about it.
 *
 * <p>The bytes are in the {@code BlobStore} under {@code sha256/<tenantId>/<hash>}; this is the
 * metadata, the scan verdict and the reference count. Content-addressed within a tenant and never
 * across (ADR-0032): two tenants holding the same photo hold two copies, so nothing about one
 * tenant's storage can be inferred from another's upload.
 */
@Entity
@Table(schema = "media", name = "media_object")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MediaObject {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "tenant_id", nullable = false, updatable = false)
  private UUID tenantId;

  /** The content address. Immutable: changing it would rename bytes that are already stored. */
  @Column(name = "sha256", nullable = false, updatable = false)
  private String sha256;

  /** Detected from magic bytes, never from the extension (REQ-MED-004). */
  @Column(name = "media_type", nullable = false)
  private String mediaType;

  @Column(name = "byte_size", nullable = false)
  private long byteSize;

  @Column(name = "width_px")
  private Integer widthPx;

  @Column(name = "height_px")
  private Integer heightPx;

  /**
   * The {@code thumb} derivative's own content address, or {@code null} until it exists.
   *
   * <p>Null is not a placeholder here, it is the answer: {@code MediaService} offers a URL only for
   * a variant whose address is set, so a client never receives a link to a file that has not been
   * produced (ADR-0051).
   */
  @Column(name = "thumb_sha256")
  private String thumbSha256;

  /** The {@code preview} derivative's content address, or {@code null} until it exists. */
  @Column(name = "preview_sha256")
  private String previewSha256;

  /**
   * When the derivation ran, whether or not it produced anything.
   *
   * <p>A PDF has no derivatives and still has to be marked done, or the worker would claim it again
   * for ever.
   */
  @Column(name = "derived_at")
  private Instant derivedAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "scan_state", nullable = false)
  private ScanState scanState;

  @Column(name = "scan_verdict")
  private String scanVerdict;

  @Column(name = "scanned_at")
  private Instant scannedAt;

  @Column(name = "ref_count", nullable = false)
  private int refCount;

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

  private MediaObject(UUID id, UUID tenantId, String sha256, String mediaType, long byteSize,
      Integer widthPx, Integer heightPx, UUID actor, Instant now) {
    this.id = id;
    this.tenantId = tenantId;
    this.sha256 = sha256;
    this.mediaType = mediaType;
    this.byteSize = byteSize;
    this.widthPx = widthPx;
    this.heightPx = heightPx;
    this.scanState = ScanState.PENDING_SCAN;
    this.refCount = 0;
    this.createdAt = now;
    this.updatedAt = now;
    this.createdBy = actor;
    this.updatedBy = actor;
  }

  /**
   * Records a newly stored blob, unjudged.
   *
   * @param id the identifier
   * @param tenantId the owning tenant
   * @param sha256 the content address
   * @param mediaType the detected type
   * @param byteSize how large it is
   * @param widthPx the width for an image, or {@code null}
   * @param heightPx the height for an image, or {@code null}
   * @param actor the uploader
   * @param now the moment of the upload
   * @return the new record, in {@link ScanState#PENDING_SCAN}
   */
  public static MediaObject pending(UUID id, UUID tenantId, String sha256, String mediaType,
      long byteSize, Integer widthPx, Integer heightPx, UUID actor, Instant now) {
    return new MediaObject(id, tenantId, sha256, mediaType, byteSize, widthPx, heightPx, actor, now);
  }

  /**
   * Records the scanner's verdict.
   *
   * @param state what the scanner concluded
   * @param verdict the signature when infected, else {@code null}
   * @param now when it concluded it
   * @throws IllegalStateException when a verdict has already been recorded. A second verdict would
   *     mean something re-judged a blob, and the only direction that could go is from INFECTED to
   *     CLEAN
   */
  public void recordVerdict(ScanState state, String verdict, Instant now) {
    if (this.scanState != ScanState.PENDING_SCAN) {
      throw new IllegalStateException(
          "Media object " + id + " already has the verdict " + this.scanState);
    }
    this.scanState = state;
    this.scanVerdict = verdict;
    this.scannedAt = now;
    this.updatedAt = now;
  }

  /**
   * Whether the bytes may be served.
   *
   * @return {@code true} only when the scanner said clean (REQ-MED-013)
   */
  public boolean isRetrievable() {
    return scanState == ScanState.CLEAN && deletedAt == null;
  }

  /**
   * Notes one more reference from an attachment.
   *
   * @param now the moment of the change
   */
  public void addReference(Instant now) {
    this.refCount++;
    this.updatedAt = now;
  }

  /**
   * Notes one fewer reference.
   *
   * @param now the moment of the change
   * @return {@code true} when nothing references the blob any more, so the bytes may go
   */
  public boolean removeReference(Instant now) {
    if (this.refCount > 0) {
      this.refCount--;
    }
    this.updatedAt = now;
    return this.refCount == 0;
  }
}
