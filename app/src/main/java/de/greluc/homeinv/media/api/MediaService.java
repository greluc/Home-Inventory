/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.media.api;

import de.greluc.homeinv.platform.Page;
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
   * Accepts an upload, stores it and attaches it — and does <b>not</b> scan it.
   *
   * <p>The scan runs in the {@code worker}, on the event this publishes (ADR-0024, ADR-0054), so
   * what comes back is an object in {@code PENDING_SCAN} with no URLs. {@link #findOne} is where the
   * verdict arrives. The REST adapter answers {@code 202} for exactly this reason.
   *
   * <p>One call rather than upload-then-attach, because an upload attached to nothing is an orphan
   * the reference count cannot explain, and stage 0 has no session to hold one in.
   *
   * @param content the incoming bytes
   * @param targetKind {@code ITEM} or {@code LOCATION}
   * @param targetId what to attach it to
   * @param primaryImage whether this becomes the image lists show (REQ-MED-002)
   * @param role what the attachment is for — {@code PHOTO}, {@code RECEIPT}, {@code
   *     WARRANTY_PROOF} or {@code OTHER}, defaulting to {@code PHOTO} when null. It is what lets
   *     the insurance report of REQ-LIFE-016 attach the receipt rather than offering a list and
   *     leaving the reader to find it
   * @param actor the uploader
   * @return the accepted file, in {@code PENDING_SCAN} and with no URLs yet
   * @throws IOException when spooling or storing fails
   */
  MediaView upload(
      InputStream content,
      String targetKind,
      UUID targetId,
      boolean primaryImage,
      String role,
      UUID actor)
      throws IOException;

  /**
   * One file of this tenant, by id — the resource an upload's {@code Location} header points at.
   *
   * <p>This is the "job resource with state" ADR-0024 promises the client. It exists because the
   * upload is answered {@code 202} before the scan has run, and polling the whole attachment list to
   * find out what became of one upload is a worse contract than asking about the upload.
   *
   * <p>The state is delivered as the status code rather than only in the body, so a client can
   * branch without parsing: {@code 200} with signed URLs when clean, {@code 422} when the scanner
   * refused it, {@code 503} while there is no verdict. That is an exception to the corpus's usual
   * rule of answering {@code 404} for anything a caller may not see — but the caller here is inside
   * the tenant and uploaded the file, so nothing is revealed that they did not put there.
   *
   * @param mediaObjectId the file
   * @return the file, with signed URLs
   * @throws de.greluc.homeinv.platform.NotFoundException when this tenant has no such live file
   * @throws MalwareDetectedException when the scanner refused it (REQ-SEC-092)
   * @throws ScannerUnavailableException when no verdict has been reached yet, or none could be
   */
  MediaView findOne(UUID mediaObjectId);

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
  Page<MediaView> attachmentsOf(String targetKind, UUID targetId, String cursor, int limit);

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
