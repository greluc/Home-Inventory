/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.media.api;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;

/**
 * Uploading files and attaching them to things.
 *
 * <p>Published; the pipeline, the scanner and the blob store stay inside the block. A caller hands
 * over a stream and receives signed URLs, and never learns where the bytes went.
 */
public interface MediaService {

  /**
   * Accepts an upload, scans it, stores it and attaches it.
   *
   * <p>One call rather than upload-then-attach, because an upload attached to nothing is an orphan
   * the reference count cannot explain, and stage 0 has no session to hold one in.
   *
   * @param content the incoming bytes
   * @param targetKind {@code ITEM} or {@code LOCATION}
   * @param targetId what to attach it to
   * @param primaryImage whether this becomes the image lists show (REQ-MED-002)
   * @param actor the uploader
   * @return the stored file, with signed URLs once it is clean
   * @throws IOException when spooling or storing fails
   */
  MediaView upload(
      InputStream content, String targetKind, UUID targetId, boolean primaryImage, UUID actor)
      throws IOException;

  /**
   * The files attached to one thing, in display order.
   *
   * @param targetKind {@code ITEM} or {@code LOCATION}
   * @param targetId what they hang on
   * @return the attachments, primary image first
   */
  List<MediaView> attachmentsOf(String targetKind, UUID targetId);

  /**
   * Detaches a file and removes the bytes when nothing references them any more.
   *
   * @param mediaObjectId the file
   * @param targetKind what it hangs on
   * @param targetId which one
   * @param actor the user detaching it
   */
  void detach(UUID mediaObjectId, String targetKind, UUID targetId, UUID actor);

  /**
   * Opens a variant for streaming, after its signature has been verified.
   *
   * @param tenantId the tenant from the signed URL
   * @param sha256 the content address from the signed URL
   * @return the bytes
   * @throws IOException when the blob is missing
   */
  InputStream openVerified(UUID tenantId, String sha256) throws IOException;
}
