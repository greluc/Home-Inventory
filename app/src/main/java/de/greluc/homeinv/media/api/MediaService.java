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
   * One page of the files attached to one thing, oldest first.
   *
   * <p>Paged, and not because anybody expects two hundred photographs of one box: {@code
   * REQ-NFR-010} admits no endpoint that loads a collection without a bound, and "this one is
   * usually small" is the reasoning behind every unbounded query that ever took a server down. The
   * cursor is the same signed, query-bound one search uses (REQ-SRCH-009).
   *
   * <p>Ordered by upload time rather than "primary first". A keyset cursor needs a total order that
   * does not change under the client's feet, and marking a different attachment primary would
   * reorder a list somebody is halfway through. The primary is a flag on each row instead
   * (REQ-MED-002).
   *
   * @param targetKind {@code ITEM} or {@code LOCATION}
   * @param targetId what they hang on
   * @param cursor an opaque cursor from a previous page, or {@code null} for the first
   * @param limit how many at most; capped at 200
   * @return the page and a cursor for the next one, or none when this was the last
   */
  MediaPage attachmentsOf(String targetKind, UUID targetId, String cursor, int limit);

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

  /**
   * One page of attachments.
   *
   * @param items the attachments on this page
   * @param nextCursor the cursor for the next page, or {@code null} when this was the last
   */
  record MediaPage(List<MediaView> items, String nextCursor) {}
}
