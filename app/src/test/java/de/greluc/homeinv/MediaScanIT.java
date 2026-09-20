/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.media.api.BlobStore;
import de.greluc.homeinv.media.api.ImageProcessor;
import de.greluc.homeinv.media.api.MediaService;
import de.greluc.homeinv.media.api.MediaView;
import de.greluc.homeinv.media.api.ScannerUnavailableException;
import de.greluc.homeinv.media.api.VirusScanner;
import de.greluc.homeinv.media.application.MediaScanRunner;
import de.greluc.homeinv.media.domain.MediaObject;
import de.greluc.homeinv.media.domain.ScanState;
import de.greluc.homeinv.media.infrastructure.MediaObjectRepository;
import de.greluc.homeinv.platform.CallerContext;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The malware scan runs in the worker, and nothing becomes retrievable without its verdict
 * (ADR-0024, ADR-0054, REQ-MED-013, REQ-SEC-092).
 *
 * <p>The counterpart to {@code DerivativeGenerationIT}, in the same shape and for the same reason:
 * the event is published by the real service, serialised, routed by a real broker and delivered to
 * the real listener. What is stubbed is ClamAV itself — a scanner whose answer each test chooses —
 * because requiring a gigabyte of signatures on every machine that builds this project would buy
 * nothing these assertions need. The real thing is exercised by {@code deploy/smoke/journey.sh},
 * with a real EICAR file, which is what REQ-SEC-092's "verified with an EICAR test file" asks for.
 *
 * <p>This test exists because the behaviour it pins down was got wrong in exactly the way a test
 * suite cannot see: the scan ran inside the request, every test passed against a stubbed scanner,
 * and the deployment answered {@code 503} for every upload because {@code api} is not on the
 * {@code scanner} segment.
 */
@DisplayName("The malware scan")
@ActiveProfiles({"test", "worker"})
@Import(MediaScanIT.ScannerUnderTest.class)
class MediaScanIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple-42";

  /**
   * What the stub scanner does next.
   *
   * <p>Static because the bean is built once for the context and the tests take turns. Each test
   * sets it before uploading and {@link #reset()} puts it back.
   */
  private static final AtomicReference<VirusScanner> BEHAVIOUR =
      new AtomicReference<>(content -> new VirusScanner.Verdict(true, null));

  @Autowired private MediaService media;
  @Autowired private MediaObjectRepository objects;
  @Autowired private AppUserRepository users;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private MediaScanRunner scanner;
  @Autowired private BlobStore blobs;

  @AfterEach
  void reset() {
    BEHAVIOUR.set(content -> new VirusScanner.Verdict(true, null));
  }

  @Test
  @DisplayName("leaves an upload PENDING_SCAN and unretrievable until the worker has judged it")
  void cleanEventually() {
    UUID userId = createUser("clean@example.org");
    UUID tenantId = provisioning.provision("Clean", userId);

    MediaView accepted = upload(tenantId, userId);

    // The upload is answered before the verdict exists. `urls` is empty and the
    // state says so: a client that rendered whatever came back would render
    // nothing, which is the correct amount (REQ-MED-013).
    assertThat(accepted.scanState()).isEqualTo(ScanState.PENDING_SCAN.name());
    assertThat(accepted.urls()).isEmpty();

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () -> {
              MediaObject object = loadAs(tenantId, accepted.id()).orElseThrow();
              assertThat(object.getScanState()).isEqualTo(ScanState.CLEAN);
              assertThat(object.isRetrievable()).isTrue();
              // Derived only after the verdict. A thumbnail of an infected file
              // would be a second blob the deletion does not reach.
              assertThat(object.getDerivedAt()).isNotNull();
            });
  }

  @Test
  @DisplayName("discards the bytes of an infected upload and derives nothing from it")
  void infected() {
    BEHAVIOUR.set(content -> new VirusScanner.Verdict(false, "Eicar-Test-Signature"));

    UUID userId = createUser("infected@example.org");
    UUID tenantId = provisioning.provision("Infected", userId);

    MediaView accepted = upload(tenantId, userId);
    assertThat(accepted.scanState()).isEqualTo(ScanState.PENDING_SCAN.name());

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () -> {
              MediaObject object = loadAs(tenantId, accepted.id()).orElseThrow();
              assertThat(object.getScanState()).isEqualTo(ScanState.INFECTED);
              assertThat(object.getScanVerdict()).isEqualTo("Eicar-Test-Signature");
              assertThat(object.isRetrievable()).isFalse();
              // The decisive one. A row that says INFECTED next to bytes still in
              // the store is one signed URL away from being served.
              assertThat(blobs.exists(tenantId, object.getSha256())).isFalse();
              // And nothing was derived from it.
              assertThat(object.getThumbSha256()).isNull();
              assertThat(object.getPreviewSha256()).isNull();
            });
  }

  @Test
  @DisplayName("the retrieval says what happened: 422 for a finding, 503 while there is no verdict")
  void theStateIsTheStatus() {
    BEHAVIOUR.set(
        content -> {
          throw new ScannerUnavailableException("no route to the scanner", null);
        });

    UUID userId = createUser("nostate@example.org");
    UUID tenantId = provisioning.provision("No verdict", userId);

    MediaView accepted = upload(tenantId, userId);

    // REQ-SEC-092's `503` after ADR-0054 moved the scan out of the upload: the
    // status moved with it, from the POST to the GET a client polls.
    assertThatThrownBy(() -> as(tenantId, userId, () -> media.findOne(accepted.id())))
        .isInstanceOf(ScannerUnavailableException.class);

    // And the object ends up SCAN_FAILED rather than looping for ever — the
    // listener records it and the retry queue holds the next attempt.
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () ->
                assertThat(loadAs(tenantId, accepted.id()).orElseThrow().getScanState())
                    .isEqualTo(ScanState.SCAN_FAILED));

    // SCAN_FAILED is the absence of a verdict, not one: a later run may record a
    // real one, which is what makes the catch-up possible at all (ADR-0054).
    BEHAVIOUR.set(content -> new VirusScanner.Verdict(true, null));
    TenantContext.runAs(
        tenantId,
        () -> transactions.executeWithoutResult(
            status -> scanner.scan(tenantId, accepted.id())));

    assertThat(loadAs(tenantId, accepted.id()).orElseThrow().getScanState())
        .isEqualTo(ScanState.CLEAN);
  }

  // -------------------------------------------------------------------------

  /**
   * A scanner whose answer each test chooses, and a stand-in for libvips.
   *
   * <p>The scanner delegates to {@link #BEHAVIOUR} rather than being rebuilt per test, because the
   * listener that calls it lives in the application context and outlives any one test method.
   */
  @TestConfiguration(proxyBeanMethods = false)
  static class ScannerUnderTest {

    /**
     * Replaces the ClamAV adapter with whatever the running test wants it to say.
     *
     * @return a scanner that consults the test's current behaviour
     */
    @Bean
    @Primary
    VirusScanner scriptedScanner() {
      return content -> BEHAVIOUR.get().scan(content);
    }

    /**
     * Replaces the libvips adapter, which is a subprocess this test would otherwise require on
     * every machine that builds the project.
     *
     * @return a processor that writes deterministic bytes per size
     */
    @Bean
    @Primary
    ImageProcessor stubImageProcessor() {
      return new ImageProcessor() {
        @Override
        public Dimensions probe(Path source) {
          return new Dimensions(1600, 1200);
        }

        @Override
        public Dimensions derive(Path source, Path target, int maxEdge, OutputFormat format) {
          try {
            Files.write(target, ("derived-at-" + maxEdge + "px").getBytes(StandardCharsets.UTF_8));
          } catch (IOException unwritable) {
            throw new UncheckedIOException(unwritable);
          }
          return new Dimensions(maxEdge, maxEdge);
        }
      };
    }
  }

  private MediaView upload(UUID tenantId, UUID userId) {
    return as(
        tenantId,
        userId,
        () -> {
          try {
            return media.upload(
                new ByteArrayInputStream(jpeg()), "ITEM", UUID.randomUUID(), false, "PHOTO", userId);
          } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
          }
        });
  }

  private UUID createUser(String email) {
    UUID userId = UUID.randomUUID();
    transactions.executeWithoutResult(
        status ->
            users.save(
                AppUser.create(
                    userId,
                    email,
                    email.substring(0, email.indexOf('@')),
                    "de",
                    passwordEncoder.encode(PASSWORD),
                    Instant.now())));
    // REQ-AUTH-003: an OWNER or ADMIN with no second factor is refused every
    // request in the tenant. The enrolment loop is proved in SecondFactorIT;
    // here it is a precondition rather than the subject.
    enrolSecondFactor(userId);
    return userId;
  }

  private <T> T as(UUID tenantId, UUID userId, java.util.function.Supplier<T> body) {
    return TenantContext.callAs(
        tenantId,
        () -> {
          AtomicReference<T> result = new AtomicReference<>();
          CallerContext.runAs(
              new CallerContext.Caller(userId, tenantId, "OWNER"),
              () -> result.set(transactions.execute(status -> body.get())));
          return result.get();
        });
  }

  private Optional<MediaObject> loadAs(UUID tenantId, UUID mediaObjectId) {
    return TenantContext.callAs(
        tenantId, () -> transactions.execute(status -> objects.findById(mediaObjectId)));
  }

  /** A minimal file whose magic bytes say JPEG. Nothing decodes it; the processor is a stub. */
  private static byte[] jpeg() {
    byte[] file = new byte[256];
    file[0] = (byte) 0xFF;
    file[1] = (byte) 0xD8;
    file[2] = (byte) 0xFF;
    file[3] = (byte) 0xE0;
    for (int i = 4; i < file.length; i++) {
      file[i] = (byte) (i % 251);
    }
    return file;
  }

}
