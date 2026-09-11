/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.media.application;

import de.greluc.homeinv.media.api.BlobStore;
import de.greluc.homeinv.media.api.ImageProcessor;
import de.greluc.homeinv.media.api.MalwareDetectedException;
import de.greluc.homeinv.media.api.MediaTypeDetector;
import de.greluc.homeinv.media.api.PayloadTooLargeException;
import de.greluc.homeinv.media.api.VirusScanner;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * What happens to an upload between arriving and being stored.
 *
 * <h2>The order is the design</h2>
 *
 * <p>Each step exists to make the next one safe, and doing them in a different order removes the
 * protection rather than merely reordering it:
 *
 * <ol>
 *   <li><b>Size, while reading.</b> {@code REQ-SEC-037} says the limit is enforced <em>before</em>
 *       reading, which in practice means while reading and never after: a limit checked on a
 *       completed file is a limit that has already let the file arrive. The stream is cut off the
 *       moment it exceeds the bound.
 *   <li><b>Type, from the first bytes.</b> The filename and the declared type are discarded
 *       ({@code REQ-MED-004}).
 *   <li><b>Dimensions, from the header only.</b> {@code REQ-SEC-040}: a decompression bomb is a
 *       small file that becomes an enormous bitmap, so the pixel count is read from the header and
 *       refused before anything decodes it. Checking after decoding is checking after the damage.
 *   <li><b>Scan, before anything is stored under its final name.</b> {@code REQ-MED-013} and
 *       ADR-0024 make it mandatory and fail-closed.
 *   <li><b>Store.</b> Only then, and only bytes that have a verdict.
 * </ol>
 *
 * <p>The upload is spooled to a temporary file rather than held in memory. A 25 MB limit times a
 * few concurrent uploads is a heap, and the file is needed twice anyway — once for the scanner and
 * once for the hash.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class UploadPipeline {

  private final BlobStore blobs;
  private final VirusScanner scanner;
  private final ImageProcessor images;

  /** REQ-SEC-037: 25 MB by default. */
  @Value("${homeinv.media.max-bytes:26214400}")
  private long maxBytes;

  /** REQ-SEC-040: 100 megapixels by default. */
  @Value("${homeinv.media.max-pixels:100000000}")
  private long maxPixels;

  /**
   * Runs an upload through every check and stores it.
   *
   * @param tenantId the owning tenant
   * @param content the incoming bytes
   * @return what was stored
   * @throws PayloadTooLargeException when the stream exceeds the size limit or the image exceeds
   *     the pixel limit
   * @throws de.greluc.homeinv.media.api.UnsupportedMediaTypeException when the detected type is not
   *     on the allowlist
   * @throws MalwareDetectedException when the scanner found something
   * @throws de.greluc.homeinv.media.api.ScannerUnavailableException when no verdict could be reached
   * @throws IOException when spooling or storing fails
   */
  public Stored accept(UUID tenantId, InputStream content) throws IOException {
    Path spool = Files.createTempFile("homeinv-upload-", ".bin");
    try {
      String sha256 = spoolAndHash(content, spool);
      long size = Files.size(spool);
      if (size == 0) {
        throw new PayloadTooLargeException("An empty upload is not a file");
      }

      MediaTypeDetector.Detected detected = MediaTypeDetector.detect(head(spool));

      Integer width = null;
      Integer height = null;
      if (detected.image()) {
        // Header only. Nothing is decoded until this has passed.
        ImageProcessor.Dimensions dimensions = images.probe(spool);
        if (dimensions.pixels() > maxPixels) {
          throw new PayloadTooLargeException(
              "The image is %d megapixels; the limit is %d"
                  .formatted(dimensions.pixels() / 1_000_000, maxPixels / 1_000_000));
        }
        width = dimensions.width();
        height = dimensions.height();
      }

      // Fail-closed: an unreachable scanner raises, and the upload is refused
      // rather than accepted unscanned (ADR-0024).
      try (InputStream forScanner = Files.newInputStream(spool)) {
        VirusScanner.Verdict verdict = scanner.scan(forScanner);
        if (!verdict.clean()) {
          // The bytes are never stored. An infected file that reaches the store
          // is an infected file somebody will later serve.
          log.warn("Discarded an upload of tenant {}: {}", tenantId, verdict.signature());
          throw new MalwareDetectedException(verdict.signature());
        }
      }

      try (InputStream forStore = Files.newInputStream(spool)) {
        blobs.store(tenantId, sha256, forStore);
      }

      return new Stored(sha256, detected.mediaType(), detected.image(), size, width, height);

    } finally {
      // The spool file holds an unscanned upload; it does not outlive the request
      // under any exit path.
      Files.deleteIfExists(spool);
    }
  }

  /**
   * Copies the stream to a file, hashing as it goes and stopping at the limit.
   *
   * @param content the incoming bytes
   * @param spool where to write them
   * @return the hex digest of what was written
   * @throws PayloadTooLargeException as soon as the limit is passed, without reading the rest
   * @throws IOException when writing fails
   */
  private String spoolAndHash(InputStream content, Path spool) throws IOException {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }

    byte[] buffer = new byte[64 * 1024];
    long total = 0;
    try (OutputStream file = Files.newOutputStream(spool);
        DigestOutputStream out = new DigestOutputStream(file, digest)) {
      int read;
      while ((read = content.read(buffer)) != -1) {
        total += read;
        if (total > maxBytes) {
          // Stops here. Reading to the end to report an accurate size would mean
          // accepting the whole oversized upload in order to refuse it.
          throw new PayloadTooLargeException(
              "The upload exceeds the limit of %d MB".formatted(maxBytes / (1024 * 1024)));
        }
        out.write(buffer, 0, read);
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  /**
   * The leading bytes, for type detection.
   *
   * @param spool the spooled upload
   * @return at most {@link MediaTypeDetector#PROBE_BYTES} bytes
   * @throws IOException when the file cannot be read
   */
  private static byte[] head(Path spool) throws IOException {
    try (InputStream in = Files.newInputStream(spool)) {
      return in.readNBytes(MediaTypeDetector.PROBE_BYTES);
    }
  }

  /**
   * What an accepted upload turned out to be.
   *
   * @param sha256 the content address it was stored under
   * @param mediaType the detected type
   * @param image whether it is an image, and so gets derivatives
   * @param byteSize how large it is
   * @param widthPx the width, or {@code null} for a document
   * @param heightPx the height, or {@code null} for a document
   */
  public record Stored(
      String sha256, String mediaType, boolean image, long byteSize, Integer widthPx, Integer heightPx) {}
}
