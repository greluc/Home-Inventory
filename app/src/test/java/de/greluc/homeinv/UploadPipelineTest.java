/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.homeinv.media.api.BlobStore;
import de.greluc.homeinv.media.api.ImageProcessor;
import de.greluc.homeinv.media.api.MalwareDetectedException;
import de.greluc.homeinv.media.api.PayloadTooLargeException;
import de.greluc.homeinv.media.api.ScannerUnavailableException;
import de.greluc.homeinv.media.api.UnsupportedMediaTypeException;
import de.greluc.homeinv.media.api.VirusScanner;
import de.greluc.homeinv.media.application.UploadPipeline;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
 * <em>counts</em> what reached it, which is how the ordering assertions below are made. "The scanner
 * was never called" is the assertion that proves an oversized file was refused before it could cost
 * a scan, and a mock that merely records calls would express that less clearly than a counter does.
 *
 * <p>No container: none of this needs one. The libvips and ClamAV adapters are exercised in CI
 * against the real services (O30); what is under test here is the logic that decides whether they
 * are reached at all.
 */
@DisplayName("The upload pipeline")
class UploadPipelineTest {

  private static final UUID TENANT = UUID.randomUUID();

  private CountingScanner scanner;
  private CountingProcessor processor;
  private InMemoryBlobStore blobs;
  private UploadPipeline pipeline;

  @BeforeEach
  void setUp() {
    scanner = new CountingScanner(true, null);
    processor = new CountingProcessor(new ImageProcessor.Dimensions(800, 600));
    blobs = new InMemoryBlobStore();
    pipeline = new UploadPipeline(blobs, scanner, processor);
    // The @Value fields are not injected outside a context.
    ReflectionTestUtils.setField(pipeline, "maxBytes", 1024L);
    ReflectionTestUtils.setField(pipeline, "maxPixels", 1_000_000L);
  }

  @Test
  @DisplayName("stores a clean image under its content address")
  void storesACleanImage() throws IOException {
    UploadPipeline.Stored stored = pipeline.accept(TENANT, new ByteArrayInputStream(jpeg(200)));

    assertThat(stored.mediaType()).isEqualTo("image/jpeg");
    assertThat(stored.image()).isTrue();
    assertThat(stored.widthPx()).isEqualTo(800);
    assertThat(stored.sha256()).hasSize(64);
    assertThat(blobs.stored).containsKey(stored.sha256());
  }

  @Test
  @DisplayName("refuses an oversized upload before it costs a scan")
  void refusesOversizedBeforeScanning() {
    assertThatThrownBy(() -> pipeline.accept(TENANT, new ByteArrayInputStream(jpeg(5000))))
        .isInstanceOf(PayloadTooLargeException.class);

    // REQ-SEC-037 says the limit is enforced before reading, which means the
    // expensive work must not have happened. A scan of a refused upload is work
    // an attacker can make us do by sending large files.
    assertThat(scanner.calls).hasValue(0);
    assertThat(blobs.stored).isEmpty();
  }

  @Test
  @DisplayName("refuses a decompression bomb before anything decodes it")
  void refusesTooManyPixelsBeforeDecoding() {
    processor.dimensions = new ImageProcessor.Dimensions(20_000, 20_000); // 400 megapixels

    assertThatThrownBy(() -> pipeline.accept(TENANT, new ByteArrayInputStream(jpeg(200))))
        .isInstanceOf(PayloadTooLargeException.class)
        .hasMessageContaining("megapixels");

    // REQ-SEC-040: refused on the header, so only probe() ran and no derivative
    // was ever produced. A limit checked after decoding is a limit checked after
    // the allocation it was meant to prevent.
    assertThat(processor.probes).hasValue(1);
    assertThat(processor.derivations).hasValue(0);
    assertThat(scanner.calls).hasValue(0);
  }

  @Test
  @DisplayName("refuses a type that is not on the allowlist, without scanning it")
  void refusesDisallowedType() {
    byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script/></svg>   ".getBytes(StandardCharsets.UTF_8);

    assertThatThrownBy(() -> pipeline.accept(TENANT, new ByteArrayInputStream(svg)))
        .isInstanceOf(UnsupportedMediaTypeException.class);

    assertThat(scanner.calls).hasValue(0);
    assertThat(blobs.stored).isEmpty();
  }

  @Test
  @DisplayName("discards an infected upload instead of storing it")
  void discardsInfected() {
    scanner = new CountingScanner(false, "Eicar-Test-Signature");
    pipeline = new UploadPipeline(blobs, scanner, processor);
    ReflectionTestUtils.setField(pipeline, "maxBytes", 1024L);
    ReflectionTestUtils.setField(pipeline, "maxPixels", 1_000_000L);

    assertThatThrownBy(() -> pipeline.accept(TENANT, new ByteArrayInputStream(jpeg(200))))
        .isInstanceOf(MalwareDetectedException.class);

    // The decisive assertion: nothing reached the store. An infected file in the
    // store is a file somebody will later serve.
    assertThat(blobs.stored).isEmpty();
  }

  @Test
  @DisplayName("refuses the upload when the scanner cannot be reached")
  void failsClosedWhenTheScannerIsDown() {
    pipeline = new UploadPipeline(blobs, new UnavailableScanner(), processor);
    ReflectionTestUtils.setField(pipeline, "maxBytes", 1024L);
    ReflectionTestUtils.setField(pipeline, "maxPixels", 1_000_000L);

    // ADR-0024: mandatory in every profile. The tempting alternative during an
    // outage - accept now, scan later - means an unscanned file in the store.
    assertThatThrownBy(() -> pipeline.accept(TENANT, new ByteArrayInputStream(jpeg(200))))
        .isInstanceOf(ScannerUnavailableException.class);
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

  /** Records how often it was asked, so "was never called" can be asserted. */
  private static final class CountingScanner implements VirusScanner {
    private final AtomicInteger calls = new AtomicInteger();
    private final boolean clean;
    private final String signature;

    CountingScanner(boolean clean, String signature) {
      this.clean = clean;
      this.signature = signature;
    }

    @Override
    public Verdict scan(InputStream content) {
      calls.incrementAndGet();
      return new Verdict(clean, signature);
    }
  }

  /** Stands in for a scanner that is down. */
  private static final class UnavailableScanner implements VirusScanner {
    @Override
    public Verdict scan(InputStream content) {
      throw new ScannerUnavailableException("no route to the scanner", null);
    }
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
