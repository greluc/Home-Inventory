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
   * The longest edge a stored image may have (REQ-MED-005).
   *
   * <p>Not a preference: a 12000-pixel photograph is decoded by every client that
   * displays it, and the pixel limit above bounds what this process decodes while
   * this bounds what everyone else has to.
   */
  private static final int MAX_EDGE_PX = 4096;

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

      if (!detected.image()) {
        // A document is stored as it arrived. There is no re-encoding that makes
        // a PDF safer without also making it a different document, and the
        // serving side refuses to render it inline anyway (REQ-SEC-045).
        try (InputStream forStore = Files.newInputStream(spool)) {
          blobs.store(tenantId, sha256, forStore);
        }
        return new Stored(sha256, detected.mediaType(), false, size, width, height);
      }

      // EVERY image is re-encoded, and what is stored is the re-encoding — never
      // the bytes that arrived (REQ-SEC-041). Three things follow from doing it
      // here rather than later:
      //
      //   * an embedded payload in a polyglot file does not survive being decoded
      //     and written out again, and it never reaches the store to begin with;
      //   * EXIF, GPS included, is gone before anything is persisted, rather than
      //     being stripped from a copy while the original keeps it
      //     (REQ-MED-006, REQ-PRIV-007);
      //   * HEIC is transcoded before it is written, which is the only way
      //     REQ-MED-003's "no stored blob has a HEIC magic number" can be true.
      //
      // AVIF for all of them, at Q=60: one output format is one decoder path to
      // reason about, and the alternative — preserving each input's format —
      // would mean JPEG stays JPEG and its metadata handling stays JPEG's.
      //
      // This is the one derivative produced synchronously. `thumb` and `preview`
      // are the worker's (ADR-0051); `full` cannot be, because until it exists
      // the only bytes on hand are the ones that must not be stored.
      Path reEncoded = Files.createTempFile("homeinv-full-", ".avif");
      try {
        ImageProcessor.Dimensions dimensions =
            images.derive(spool, reEncoded, MAX_EDGE_PX, ImageProcessor.OutputFormat.AVIF);
        String derivedSha = hashOf(reEncoded);
        try (InputStream forStore = Files.newInputStream(reEncoded)) {
          blobs.store(tenantId, derivedSha, forStore);
        }
        return new Stored(
            derivedSha,
            "image/avif",
            true,
            Files.size(reEncoded),
            dimensions.width(),
            dimensions.height());
      } finally {
        Files.deleteIfExists(reEncoded);
      }

    } finally {
      // The spool file holds an unscanned upload; it does not outlive the request
      // under any exit path.
      Files.deleteIfExists(spool);
    }
  }

  /**
   * The SHA-256 of a file, as the lowercase hex the blob layout uses.
   *
   * @param file the file to hash
   * @return the digest
   * @throws IOException when the file cannot be read
   */
  private static String hashOf(Path file) throws IOException {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
    try (InputStream bytes = Files.newInputStream(file)) {
      byte[] buffer = new byte[8192];
      int read;
      while ((read = bytes.read(buffer)) > 0) {
        digest.update(buffer, 0, read);
      }
    }
    return HexFormat.of().formatHex(digest.digest());
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
