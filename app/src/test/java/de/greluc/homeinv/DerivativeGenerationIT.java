/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.media.api.ImageProcessor;
import de.greluc.homeinv.media.api.MediaService;
import de.greluc.homeinv.media.api.VirusScanner;
import de.greluc.homeinv.media.application.DerivativeGenerator;
import de.greluc.homeinv.media.domain.MediaObject;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * An upload in one role produces derivatives in another (REQ-MED-005, ADR-0051).
 *
 * <p>The {@code worker} profile is active alongside {@code test}, so the consumer this
 * <em>process</em> registers is the one the {@code worker} <em>role</em> would register. That is
 * the compromise this test makes and the only one: the event is published by the real service,
 * serialised, routed by a real broker, delivered to the real listener, and the columns are read
 * back from PostgreSQL.
 *
 * <p>{@code libvips} is replaced, because it is a subprocess this test would otherwise require on
 * every machine and CI runner that builds the project. What is under test is the handover, not
 * whether libvips can resize — that is the adapter's own business and is exercised by the image
 * job.
 */
@DisplayName("Derivative generation")
@ActiveProfiles({"test", "worker"})
@Import(DerivativeGenerationIT.StubImageProcessor.class)
class DerivativeGenerationIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple-42";

  @Autowired private MediaService media;
  @Autowired private MediaObjectRepository objects;
  @Autowired private AppUserRepository users;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private DerivativeGenerator generator;

  @Test
  @DisplayName("an uploaded image gains thumb and preview without the request waiting for them")
  void derivativesAppearAfterTheUpload() {
    UUID userId = createUser("derive@example.org");
    UUID tenantId = provisioning.provision("Deriving", userId);

    UUID mediaObjectId = uploadJpeg(tenantId, userId);

    // The upload returned before the derivatives existed - that is the point of
    // moving them off the request thread - so the assertion waits for the
    // consumer rather than assuming it has already run.
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(
            () -> {
              Optional<MediaObject> object = loadAs(tenantId, mediaObjectId);
              assertThat(object).isPresent();
              assertThat(object.get().getDerivedAt()).isNotNull();
              assertThat(object.get().getThumbSha256()).isNotNull().hasSize(64);
              assertThat(object.get().getPreviewSha256()).isNotNull().hasSize(64);
              // Each derivative is its own content, so its address differs from
              // the full variant's and from the other derivative's.
              assertThat(object.get().getThumbSha256())
                  .isNotEqualTo(object.get().getPreviewSha256())
                  .isNotEqualTo(object.get().getSha256());
            });
  }

  @Test
  @DisplayName("a second delivery of the same event changes nothing (REQ-NFR-013)")
  void derivationIsIdempotent() {
    UUID userId = createUser("idempotent@example.org");
    UUID tenantId = provisioning.provision("Idempotence", userId);
    UUID mediaObjectId = uploadJpeg(tenantId, userId);

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> assertThat(loadAs(tenantId, mediaObjectId).orElseThrow().getDerivedAt())
                .isNotNull());

    MediaObject first = loadAs(tenantId, mediaObjectId).orElseThrow();

    // Delivery is at-least-once, so this happens as a matter of course after a
    // broker outage. Running it again must not produce a second set of blobs or
    // move the timestamp - a client polling `derivedAt` would otherwise see the
    // work appear to restart.
    TenantContext.runAs(tenantId, () -> generator.derive(tenantId, mediaObjectId));

    MediaObject second = loadAs(tenantId, mediaObjectId).orElseThrow();
    assertThat(second.getDerivedAt()).isEqualTo(first.getDerivedAt());
    assertThat(second.getThumbSha256()).isEqualTo(first.getThumbSha256());
    assertThat(second.getPreviewSha256()).isEqualTo(first.getPreviewSha256());
  }

  @Test
  @DisplayName("a document is marked derived with no derivatives, so it is not claimed for ever")
  void aDocumentIsMarkedDone() {
    UUID userId = createUser("document@example.org");
    UUID tenantId = provisioning.provision("Documents", userId);

    UUID mediaObjectId =
        as(
            tenantId,
            userId,
            () -> {
              try {
                return media
                    .upload(new ByteArrayInputStream(pdf()), "ITEM", UUID.randomUUID(), false, "PHOTO", userId)
                    .id();
              } catch (IOException unreadable) {
                throw new UncheckedIOException(unreadable);
              }
            });

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> {
              MediaObject object = loadAs(tenantId, mediaObjectId).orElseThrow();
              // Finished, with nothing produced. Without the timestamp the worker
              // would claim this row again on every redelivery for the life of
              // the object.
              assertThat(object.getDerivedAt()).isNotNull();
              assertThat(object.getThumbSha256()).isNull();
              assertThat(object.getPreviewSha256()).isNull();
              // And the document is stored as it arrived: there is no re-encoding
              // that makes a PDF safer without making it a different document.
              assertThat(object.getMediaType()).isEqualTo("application/pdf");
            });
  }

  // -------------------------------------------------------------------------

  /**
   * A stand-in for libvips.
   *
   * <p>Writes a file whose content depends on the requested edge length, so two different sizes
   * hash differently — which is what makes "each derivative has its own content address" an
   * assertion rather than a coincidence.
   */
  @TestConfiguration(proxyBeanMethods = false)
  static class StubImageProcessor {

    /**
     * Replaces the ClamAV adapter.
     *
     * <p>Without this the worker reaches no verdict and never derives anything, because derivation
     * happens only after a clean scan (ADR-0054) — so this stub is what lets the test reach the
     * behaviour it is about. The scan itself has {@code MediaScanIT} and a real container in the
     * smoke suite; requiring a 1 GB signature database on every machine that runs this one test
     * would buy nothing it proves.
     *
     * @return a scanner that finds nothing
     */
    @Bean
    @Primary
    VirusScanner cleanScanner() {
      return content -> new VirusScanner.Verdict(true, null);
    }

    /**
     * Replaces the libvips adapter.
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
            Files.write(
                target, ("derived-at-" + maxEdge + "px").getBytes(StandardCharsets.UTF_8));
          } catch (IOException unwritable) {
            throw new UncheckedIOException(unwritable);
          }
          return new Dimensions(maxEdge, maxEdge);
        }
      };
    }
  }

  private UUID uploadJpeg(UUID tenantId, UUID userId) {
    return as(
        tenantId,
        userId,
        () -> {
          try {
            return media
                .upload(
                    new ByteArrayInputStream(jpeg()), "ITEM", UUID.randomUUID(), false, "PHOTO", userId)
                .id();
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
          java.util.concurrent.atomic.AtomicReference<T> result =
              new java.util.concurrent.atomic.AtomicReference<>();
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

  /** A minimal file whose magic bytes say PDF. */
  private static byte[] pdf() {
    byte[] file = new byte[256];
    System.arraycopy("%PDF-1.7".getBytes(StandardCharsets.UTF_8), 0, file, 0, 8);
    return file;
  }

  /** A minimal file whose magic bytes say JPEG. Nothing decodes it; the processor is a stub. */
  private static byte[] jpeg() {
    byte[] file = new byte[256];
    file[0] = (byte) 0xFF;
    file[1] = (byte) 0xD8;
    file[2] = (byte) 0xFF;
    file[3] = (byte) 0xE0;
    return file;
  }
}
