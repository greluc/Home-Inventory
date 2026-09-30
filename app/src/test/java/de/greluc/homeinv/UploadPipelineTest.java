/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.homeinv.media.api.BlobStore;
import de.greluc.homeinv.media.api.ImageProcessor;
import de.greluc.homeinv.platform.PayloadTooLargeException;
import de.greluc.homeinv.media.api.UnsupportedMediaTypeException;
import de.greluc.homeinv.media.application.UploadPipeline;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Proves the upload pipeline refuses what it must, in the order it must.
 *
 * <p>Hand-written stand-ins rather than a mocking framework, and not out of preference: each one
 * <em>counts</em> what reached it, which is how the ordering assertions below are made. "Nothing was
 * decoded and nothing was stored" is the assertion that proves an oversized file was refused before
 * it could cost anything, and a mock that merely records calls would express that less clearly than
 * a counter does.
 *
 * <p><b>No scanner here any more.</b> Since ADR-0054 the scan runs in the {@code worker}, on the
 * message the upload publishes — {@code MediaScanIT} covers it. What this class still owns is the
 * ordering of the checks that DO happen in the request, and the guarantee that for an image the
 * bytes reaching the store are the re-encoding and never the ones that arrived.
 *
 * <p>No container: none of this needs one. The libvips and ClamAV adapters are exercised in CI
 * against the real services (O30); what is under test here is the logic that decides whether they
 * are reached at all.
 */
@DisplayName("The upload pipeline")
class UploadPipelineTest {

  private static final UUID TENANT = UUID.randomUUID();

  private CountingProcessor processor;
  private InMemoryBlobStore blobs;
  private UploadPipeline pipeline;

  @BeforeEach
  void setUp() {
    processor = new CountingProcessor(new ImageProcessor.Dimensions(800, 600));
    blobs = new InMemoryBlobStore();
    pipeline = new UploadPipeline(blobs, processor);
    ReflectionTestUtils.setField(pipeline, "maxBytes", 1024L);
    ReflectionTestUtils.setField(pipeline, "maxPixels", 1_000_000L);
  }

  @Test
  @DisplayName("stores the RE-ENCODED image, never the bytes that arrived (REQ-SEC-041)")
  void storesTheReEncodedImage() throws IOException {
    byte[] arrived = jpeg(200);
    UploadPipeline.Stored stored = pipeline.accept(TENANT, new ByteArrayInputStream(arrived));

    assertThat(stored.mediaType()).isEqualTo("image/avif");
    assertThat(stored.image()).isTrue();
    assertThat(stored.widthPx()).isEqualTo(800);
    assertThat(stored.sha256()).hasSize(64);

    assertThat(blobs.stored).containsOnlyKeys(stored.sha256());
    assertThat(blobs.stored.get(stored.sha256())).isNotEqualTo(arrived);
    assertThat(processor.derivations).hasValue(1);
  }

  @Test
  @DisplayName("never writes a HEIC blob: it is transcoded before anything is stored (REQ-MED-003)")
  void heicIsTranscodedBeforeStoring() throws IOException {
    UploadPipeline.Stored stored = pipeline.accept(TENANT, new ByteArrayInputStream(heic(200)));

    assertThat(stored.mediaType()).isEqualTo("image/avif");
    byte[] persisted = blobs.stored.get(stored.sha256());
    assertThat(new String(persisted, StandardCharsets.UTF_8)).doesNotContain("ftypheic");
  }

  @Test
  @DisplayName("refuses an oversized upload before it costs a decode")
  void refusesOversizedBeforeDecoding() {
    assertThatThrownBy(() -> pipeline.accept(TENANT, new ByteArrayInputStream(jpeg(5000))))
        .isInstanceOf(PayloadTooLargeException.class);

    assertThat(processor.probes).hasValue(0);
    assertThat(blobs.stored).isEmpty();
  }

  @Test
  @DisplayName("refuses a decompression bomb before anything decodes it")
  void refusesTooManyPixelsBeforeDecoding() {
    processor.dimensions = new ImageProcessor.Dimensions(20_000, 20_000);

    assertThatThrownBy(() -> pipeline.accept(TENANT, new ByteArrayInputStream(jpeg(200))))
        .isInstanceOf(PayloadTooLargeException.class)
        .hasMessageContaining("megapixels");

    assertThat(processor.probes).hasValue(1);
    assertThat(processor.derivations).hasValue(0);
    assertThat(blobs.stored).isEmpty();
  }

  @Test
  @DisplayName("refuses a type that is not on the allowlist, without storing it")
  void refusesDisallowedType() {
    byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script/></svg>   ".getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(() -> pipeline.accept(TENANT, new ByteArrayInputStream(svg)))
        .isInstanceOf(UnsupportedMediaTypeException.class);

    assertThat(blobs.stored).isEmpty();
  }

  @Test
  @DisplayName("gives identical bytes the same address, so a re-upload costs nothing")
  void identicalBytesShareAnAddress() throws IOException {
    UploadPipeline.Stored first = pipeline.accept(TENANT, new ByteArrayInputStream(jpeg(200)));
    UploadPipeline.Stored second = pipeline.accept(TENANT, new ByteArrayInputStream(jpeg(200)));

    assertThat(second.sha256()).isEqualTo(first.sha256());
    assertThat(blobs.stored).hasSize(1);
  }

  @Test
  @DisplayName("refuses an empty upload")
  void refusesEmpty() {
    assertThatThrownBy(() -> pipeline.accept(TENANT, new ByteArrayInputStream(new byte[0])))
        .isInstanceOf(PayloadTooLargeException.class);
  }

  /** A JPEG-signatured buffer of the requested length. */
  /**
   * A file whose magic bytes say HEIC.
   *
   * <p>The ISO base media box at offset 4 is what distinguishes HEIC from AVIF; the rest is
   * padding, because nothing here decodes it.
   *
   * @param length how many bytes in total
   * @return the fixture
   */
  private static byte[] heic(int length) {
    byte[] file = new byte[length];
    file[0] = 0;
    file[1] = 0;
    file[2] = 0;
    file[3] = 0x18;
    System.arraycopy("ftypheic".getBytes(StandardCharsets.UTF_8), 0, file, 4, 8);
    return file;
  }

  private static byte[] jpeg(int length) {
    byte[] b = new byte[length];
    b[0] = (byte) 0xFF;
    b[1] = (byte) 0xD8;
    b[2] = (byte) 0xFF;
    b[3] = (byte) 0xE0;
    for (int i = 4; i < length; i++) {
      b[i] = (byte) (i % 251);
    }
    return b;
  }

  /** Counts probes and derivations separately, which is what the ordering test needs. */
  private static final class CountingProcessor implements ImageProcessor {
    private final AtomicInteger probes = new AtomicInteger();
    private final AtomicInteger derivations = new AtomicInteger();
    private Dimensions dimensions;

    CountingProcessor(Dimensions dimensions) {
      this.dimensions = dimensions;
    }

    @Override
    public Dimensions probe(Path source) {
      probes.incrementAndGet();
      return dimensions;
    }

    @Override
    public Dimensions derive(Path source, Path target, int maxEdge, OutputFormat format) {
      derivations.incrementAndGet();
      try {
        Files.write(target, ("re-encoded:" + format + ":" + maxEdge).getBytes(StandardCharsets.UTF_8));
      } catch (IOException unwritable) {
        throw new java.io.UncheckedIOException(unwritable);
      }
      return dimensions;
    }
  }

  /** A blob store in a map, so "nothing was stored" is one assertion. */
  private static final class InMemoryBlobStore implements BlobStore {
    private final Map<String, byte[]> stored = new HashMap<>();

    @Override
    public boolean store(UUID tenantId, String sha256, InputStream content) throws IOException {
      if (stored.containsKey(sha256)) {
        return false;
      }
      stored.put(sha256, content.readAllBytes());
      return true;
    }

    @Override
    public InputStream open(UUID tenantId, String sha256) {
      return new ByteArrayInputStream(stored.get(sha256));
    }

    @Override
    public boolean exists(UUID tenantId, String sha256) {
      return stored.containsKey(sha256);
    }

    @Override
    public void delete(UUID tenantId, String sha256) {
      stored.remove(sha256);
    }
  }
}
