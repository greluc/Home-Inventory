/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.media.api;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/**
 * An upload that survives a broken connection (REQ-MED-008).
 *
 * <h2>Why this is a second way in and not a replacement</h2>
 *
 * <p>{@link MediaService#upload} takes a whole file in one request, which is the right shape for a
 * two-megabyte photograph and the wrong one for anything on a connection that drops: a request that
 * fails at 90 % has to start again from zero. This one takes the same file in pieces, and a client
 * that lost its connection asks where it got to and carries on.
 *
 * <p>Both end in the same place. When the last byte arrives, this hands the assembled stream to
 * {@code MediaService.upload} — the same size check, the same magic-byte detection, the same EXIF
 * stripping, the same transcoding, the same scan. That is deliberate and is what
 * {@code TwoWaysInIT} holds: two entrances to one pipeline is a design, two pipelines is a defect
 * waiting for one of them to be forgotten.
 *
 * <h2>Where the bytes are while they wait</h2>
 *
 * <p>In the {@code blobstore} service, which holds the volume. Not in {@code api}, which is
 * stateless by requirement (REQ-NFR-008) and may be several processes — an upload begun against one
 * replica has to be continuable against another.
 */
public interface ResumableUploads {

  /**
   * Begins an upload and reserves nothing but a place to put it.
   *
   * <p>The declared length is checked here, <b>before a byte arrives</b>, which is what
   * REQ-SEC-037 asks for and what a one-shot upload can only approximate by cutting the stream off
   * mid-read.
   *
   * @param targetKind {@code ITEM} or {@code LOCATION}
   * @param targetId what the finished file will hang on
   * @param primaryImage whether it becomes the image lists show
   * @param role what the attachment is for — {@code PHOTO}, {@code RECEIPT}, {@code
   *     WARRANTY_PROOF} or {@code OTHER}
   * @param declaredLength how many bytes the client will send
   * @param actor who is uploading
   * @return the new upload, at offset zero
   * @throws de.greluc.homeinv.media.api.PayloadTooLargeException when the declared length exceeds
   *     the ceiling
   */
  UploadSessionView begin(
      String targetKind,
      UUID targetId,
      boolean primaryImage,
      String role,
      long declaredLength,
      UUID actor);

  /**
   * Where an upload stands.
   *
   * <p>The offset comes from the store rather than from this tenant's row, because the store is
   * what actually holds the bytes.
   *
   * @param uploadId the upload
   * @return how much has arrived, and what it has become if it is finished
   * @throws de.greluc.homeinv.platform.NotFoundException when this tenant has no such upload, or it
   *     has expired
   */
  UploadSessionView status(UUID uploadId);

  /**
   * Appends bytes, and finishes the upload when they were the last ones.
   *
   * @param uploadId the upload
   * @param offset where these bytes go; it must be where the upload stands
   * @param content the bytes
   * @param actor who is uploading
   * @return where the upload stands afterwards, carrying the media id when it is done
   * @throws OffsetMismatchException when the offset is not where the upload stands
   * @throws UploadBusyException when another append is in flight for this upload
   * @throws IOException when the bytes cannot be read or staged
   */
  UploadSessionView append(UUID uploadId, long offset, InputStream content, UUID actor)
      throws IOException;

  /**
   * Abandons an upload and discards what arrived.
   *
   * @param uploadId the upload
   * @throws de.greluc.homeinv.platform.NotFoundException when this tenant has no such upload
   */
  void abort(UUID uploadId);
}
