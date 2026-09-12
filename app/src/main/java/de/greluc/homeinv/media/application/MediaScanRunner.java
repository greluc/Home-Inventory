/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.media.application;

import de.greluc.homeinv.media.api.BlobStore;
import de.greluc.homeinv.media.api.ScannerUnavailableException;
import de.greluc.homeinv.media.api.VirusScanner;
import de.greluc.homeinv.media.domain.Attachment;
import de.greluc.homeinv.media.domain.MediaObject;
import de.greluc.homeinv.media.domain.ScanState;
import de.greluc.homeinv.media.infrastructure.AttachmentRepository;
import de.greluc.homeinv.media.infrastructure.MediaObjectRepository;
import de.greluc.homeinv.platform.TenantContext;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The malware scan, where ADR-0024 says it belongs: in the {@code worker}, not in the request path.
 *
 * <h2>What "fail-closed" means once the scan is asynchronous</h2>
 *
 * <p>It cannot mean "the upload is refused" any more — the upload was answered {@code 202} long
 * before this runs. It means the object never becomes retrievable without a verdict: {@code
 * MediaObject.isRetrievable} is {@code CLEAN} and nothing else, no signed URL is minted for any
 * other state, and the serving path checks the state again rather than trusting that (REQ-MED-013,
 * REQ-SEC-092).
 *
 * <h2>The three outcomes</h2>
 *
 * <ul>
 *   <li><b>Clean.</b> The verdict is recorded and the caller goes on to derive the variants.
 *   <li><b>A finding.</b> The blob is deleted from the store, every attachment of it is detached,
 *       {@code INFECTED} is recorded with the signature name, and the variants are never derived.
 *       The media row stays, because the client is polling for exactly this answer and a vanished
 *       object would read as a lost upload — but it hangs on nothing, or an item would list a
 *       picture whose bytes are gone, possibly as its primary image (REQ-MED-002).
 *   <li><b>No verdict.</b> {@link ScannerUnavailableException} propagates. The listener retries it
 *       and, when the attempts are exhausted, records {@link ScanState#SCAN_FAILED}; the retry queue in
 *       {@code MediaMessagingConfiguration} brings it back later (ADR-0054).
 * </ul>
 *
 * <h2>Idempotent, because delivery is at-least-once</h2>
 *
 * <p>An object that already holds a real verdict is left alone and reported as it stands. The outbox
 * redelivers after a broker outage as a matter of course, and a second scan of the same bytes would
 * at best waste a minute of ClamAV and at worst try to re-judge a blob that has already been
 * deleted.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaScanRunner {

  private final MediaObjectRepository objects;
  private final AttachmentRepository attachments;
  private final BlobStore blobs;
  private final VirusScanner scanner;
  private final Clock clock;

  /**
   * Scans one stored object and records what the scanner concluded.
   *
   * @param tenantId the owning tenant, whose context every query below needs
   * @param mediaObjectId the object to scan
   * @return the state the object now holds, or {@link ScanState#PENDING_SCAN} when the object was
   *     gone before this ran and there was nothing to judge
   * @throws ScannerUnavailableException when no verdict could be reached; the caller decides whether
   *     to retry or to record {@link ScanState#SCAN_FAILED}
   */
  @Transactional
  public ScanState scan(UUID tenantId, UUID mediaObjectId) {
    TenantContext.require();

    Optional<MediaObject> found = objects.findById(mediaObjectId);
    if (found.isEmpty()) {
      // Deleted between the publish and the delivery. Not an error: the outbox
      // guarantees the event arrives, not that its subject still exists.
      log.debug("Media object {} is gone; nothing to scan.", mediaObjectId);
      return ScanState.PENDING_SCAN;
    }

    MediaObject object = found.get();
    ScanState current = object.getScanState();
    if (current == ScanState.CLEAN || current == ScanState.INFECTED) {
      log.debug("Media object {} already holds the verdict {}.", mediaObjectId, current);
      return current;
    }

    VirusScanner.Verdict verdict;
    try (InputStream stored = blobs.open(tenantId, object.getSha256())) {
      // Throws ScannerUnavailableException, which is the whole point: no verdict
      // must ever be turned into a clean one here.
      verdict = scanner.scan(stored);
    } catch (IOException unreadable) {
      // The bytes are not there to scan. That is not "clean", and it is not a
      // scanner outage either — retrying will not make the blob reappear — so it
      // is recorded as a failed scan and left to the catch-up run, which will
      // report the same thing again and keep the object unretrievable.
      log.warn(
          "Could not read the bytes of media object {} of tenant {}: {}",
          mediaObjectId,
          tenantId,
          unreadable.getMessage());
      throw new ScannerUnavailableException(
          "The stored bytes of media object " + mediaObjectId + " could not be read", unreadable);
    }

    Instant now = Instant.now(clock);
    if (verdict.clean()) {
      object.recordVerdict(ScanState.CLEAN, null, now);
      objects.save(object);
      log.debug("Media object {} of tenant {} is clean.", mediaObjectId, tenantId);
      return ScanState.CLEAN;
    }

    // The bytes go first. An infected blob that stays in the store is an
    // infected blob somebody will later serve, and the row alone is enough to
    // tell the uploader what happened.
    try {
      blobs.delete(tenantId, object.getSha256());
    } catch (IOException undeletable) {
      // Recorded and carried on with. Leaving the object PENDING_SCAN because a
      // delete failed would leave a KNOWN infected blob in a state whose meaning
      // is "not yet judged", and the retrieval path would then be the only thing
      // standing between it and a client.
      log.error(
          "Media object {} of tenant {} is infected and its blob could not be deleted: {}",
          mediaObjectId,
          tenantId,
          undeletable.getMessage());
    }

    // Detached from everything. The upload was accepted before the verdict, so
    // this file may already be an item's primary image; leaving it there would
    // make a list show a picture that cannot be served.
    for (Attachment attachment : attachments.findLiveOf(tenantId, mediaObjectId)) {
      attachment.markDeleted(object.getCreatedBy(), now);
      attachments.save(attachment);
      object.removeReference(now);
    }

    object.recordVerdict(ScanState.INFECTED, verdict.signature(), now);
    objects.save(object);
    // WARN and not INFO: an infected upload is a security event. The signature
    // name is the scanner's, not the uploader's, so it carries nothing of the
    // file itself (REQ-NFR-042 forbids user content in logs).
    log.warn(
        "Media object {} of tenant {} was refused by the scanner: {}",
        mediaObjectId,
        tenantId,
        verdict.signature());
    return ScanState.INFECTED;
  }

  /**
   * Records that no verdict could be reached, after the caller has stopped retrying.
   *
   * <p>Separate from {@link #scan} and in its own transaction, because it runs when {@code scan}'s
   * transaction has already rolled back. It is the durable half of "ask again later": the queue
   * holds the next attempt, and this row is what a client polling the object sees in the meantime.
   *
   * @param tenantId the owning tenant
   * @param mediaObjectId the object no verdict could be reached for
   */
  @Transactional
  public void recordScanFailed(UUID tenantId, UUID mediaObjectId) {
    TenantContext.require();
    objects
        .findById(mediaObjectId)
        .filter(object -> object.getScanState() == ScanState.PENDING_SCAN)
        .ifPresent(
            object -> {
              object.recordVerdict(ScanState.SCAN_FAILED, null, Instant.now(clock));
              objects.save(object);
              log.warn(
                  "No verdict for media object {} of tenant {}; it stays unretrievable and the "
                      + "catch-up run will ask again.",
                  mediaObjectId,
                  tenantId);
            });
  }
}
