/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.media.api.ImageProcessor;
import de.greluc.homeinv.media.api.MediaService;
import de.greluc.homeinv.media.api.MediaView;
import de.greluc.homeinv.media.api.OffsetMismatchException;
import de.greluc.homeinv.media.api.ResumableUploads;
import de.greluc.homeinv.media.api.UploadSessionView;
import de.greluc.homeinv.media.api.VirusScanner;
import de.greluc.homeinv.media.application.ExpiredUploadSweep;
import de.greluc.homeinv.platform.CallerContext;
import de.greluc.homeinv.platform.NotFoundException;
import de.greluc.homeinv.platform.PayloadTooLargeException;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * An interrupted upload is continued, not restarted (REQ-MED-008).
 *
 * <p>That sentence is the requirement's whole acceptance criterion, and it is one property: after a
 * connection drops, the client asks where the upload got to and sends the rest. Everything else
 * here is what makes that answer trustworthy — the offset comes from the store that holds the
 * bytes, a client that guesses wrong writes nothing, and the file that results is the same one the
 * one-shot upload would have produced.
 *
 * <p>The last of those is the one worth stating plainly: <b>two entrances, one pipeline</b>. The
 * resumable path does not re-implement the size check, the magic-byte detection, the EXIF
 * stripping, the transcoding or the scan — it hands the assembled stream to the same
 * {@link MediaService#upload} — and {@code twoEntrancesOnePipeline} is what holds that to it.
 */
@DisplayName("An upload that survives a broken connection")
@Import(ResumableUploadIT.StubUploadDependencies.class)
class ResumableUploadIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple-42";

  @Autowired private ResumableUploads uploads;
  @Autowired private de.greluc.homeinv.media.infrastructure.MediaObjectRepository objects;
  @Autowired private org.springframework.jdbc.core.simple.JdbcClient jdbc;
  @Autowired private MediaService media;
  @Autowired private ExpiredUploadSweep sweep;
  @Autowired private AppUserRepository users;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;

  @Test
  @DisplayName("is continued from where it stopped, not begun again")
  void theInterruptedUploadContinues() {
    Context context = tenant("continue");
    UUID item = UUID.randomUUID();
    // Text rather than an image, because a document is stored AS IT ARRIVED
    // while every image is re-encoded (ADR-0052) — and what this test is about
    // is whether the two pieces became the original file, which only the
    // unmodified path can say with a byte count.
    byte[] file = text(4096);

    UploadSessionView begun =
        as(context, () -> uploads.begin("ITEM", item, false, "PHOTO", file.length, context.userId()));
    assertThat(begun.offset()).isZero();

    // The first half arrives, and then the connection drops: nothing tells the
    // server, and the client does not know how much got through.
    as(context, () -> append(begun.id(), 0, Arrays.copyOfRange(file, 0, 1500), context));

    // So it asks. This is the request the whole feature exists for.
    UploadSessionView afterTheDrop = as(context, () -> uploads.status(begun.id()));
    assertThat(afterTheDrop.offset())
        .as("the offset comes from the store that holds the bytes, not from either side's belief")
        .isEqualTo(1500);
    assertThat(afterTheDrop.isComplete()).isFalse();

    // And continues from there rather than from zero.
    UploadSessionView done =
        as(
            context,
            () ->
                append(
                    begun.id(), 1500, Arrays.copyOfRange(file, 1500, file.length), context));

    assertThat(done.isComplete()).isTrue();
    assertThat(done.offset()).isEqualTo(file.length);

    // Read from the repository rather than through `findOne`, which answers a
    // PENDING_SCAN object with an exception by design (REQ-MED-013): the scan
    // runs in the worker and there is no worker here.
    long storedSize =
        as(
            context,
            () ->
                objects
                    .findById(done.mediaObjectId())
                    .orElseThrow()
                    .getByteSize());
    assertThat(storedSize)
        .as("the file that arrived in two pieces is the whole file")
        .isEqualTo(file.length);
  }

  @Test
  @DisplayName("refuses bytes offered at the wrong place, and says where it really is")
  void aWrongOffsetWritesNothing() {
    Context context = tenant("offset");
    byte[] file = jpeg(2, 2048);
    UploadSessionView begun =
        as(
            context,
            () ->
                uploads.begin(
                    "ITEM", UUID.randomUUID(), false, "PHOTO", file.length, context.userId()));

    as(context, () -> append(begun.id(), 0, Arrays.copyOfRange(file, 0, 1000), context));

    assertThatThrownBy(
            () ->
                as(
                    context,
                    () ->
                        append(
                            begun.id(), 900, Arrays.copyOfRange(file, 900, 1900), context)))
        .isInstanceOf(OffsetMismatchException.class)
        // The refusal carries the answer, so a client's next request is the
        // right one rather than another guess.
        .extracting(thrown -> ((OffsetMismatchException) thrown).actual())
        .isEqualTo(1000L);

    assertThat(as(context, () -> uploads.status(begun.id())).offset())
        .as("a refused append wrote nothing")
        .isEqualTo(1000);
  }

  @Test
  @DisplayName("refuses an oversized file before a byte of it arrives (REQ-SEC-037)")
  void theCeilingIsCheckedFirst() {
    Context context = tenant("ceiling");

    assertThatThrownBy(
            () ->
                as(
                    context,
                    () ->
                        uploads.begin(
                            "ITEM",
                            UUID.randomUUID(),
                            false,
                            "PHOTO",
                            26_214_401L,
                            context.userId())))
        .isInstanceOf(PayloadTooLargeException.class);
  }

  @Test
  @DisplayName("belongs to the tenant that began it and to nobody else")
  void anotherTenantSeesNothing() {
    Context mine = tenant("mine");
    Context theirs = tenant("theirs");
    UploadSessionView begun =
        as(
            mine,
            () -> uploads.begin("ITEM", UUID.randomUUID(), false, "PHOTO", 512, mine.userId()));

    // 404 and not 403: an upload of another tenant is one this caller cannot be
    // told exists, which is the rule the whole API follows (REQ-SEC-025).
    assertThatThrownBy(() -> as(theirs, () -> uploads.status(begun.id())))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  @DisplayName("ends in the same pipeline the one-shot upload does")
  void twoEntrancesOnePipeline() {
    Context context = tenant("entrances");
    UUID item = UUID.randomUUID();
    byte[] file = jpeg(3, 1024);

    MediaView atOnce =
        as(
            context,
            () -> {
              try {
                return media.upload(
                    new ByteArrayInputStream(file), "ITEM", item, false, "PHOTO", context.userId());
              } catch (IOException unreadable) {
                throw new UncheckedIOException(unreadable);
              }
            });

    // The same bytes, in three pieces, to a second item so the attachment is a
    // second one rather than a duplicate.
    UUID other = UUID.randomUUID();
    UploadSessionView begun =
        as(context, () -> uploads.begin("ITEM", other, false, "PHOTO", file.length, context.userId()));
    as(context, () -> append(begun.id(), 0, Arrays.copyOfRange(file, 0, 400), context));
    as(context, () -> append(begun.id(), 400, Arrays.copyOfRange(file, 400, 800), context));
    UploadSessionView done =
        as(context, () -> append(begun.id(), 800, Arrays.copyOfRange(file, 800, 1024), context));

    // Content addressing is what proves it: the same bytes through two
    // entrances are ONE stored object, which can only be true if both ran the
    // same detection, the same transcoding and the same hashing (ADR-0032).
    assertThat(done.mediaObjectId())
        .as("the same file through either entrance is the same stored object")
        .isEqualTo(atOnce.id());
  }

  @Test
  @DisplayName("is idempotent when the client loses the last response")
  void theLastAnswerCanBeAskedForAgain() {
    Context context = tenant("idempotent");
    byte[] file = jpeg(4, 600);
    UploadSessionView begun =
        as(
            context,
            () ->
                uploads.begin(
                    "ITEM", UUID.randomUUID(), false, "PHOTO", file.length, context.userId()));
    UploadSessionView done = as(context, () -> append(begun.id(), 0, file, context));

    // The client never saw that answer and asks again. It is told the same
    // media id rather than being sent back to the beginning of a file that is
    // already stored.
    UploadSessionView again = as(context, () -> uploads.status(begun.id()));
    assertThat(again.mediaObjectId()).isEqualTo(done.mediaObjectId());
    assertThat(again.offset()).isEqualTo(file.length);
  }

  @Test
  @DisplayName("is removed when nobody comes back for it")
  void theSweepTakesWhatWasAbandoned() {
    Context context = tenant("abandoned");
    byte[] file = jpeg(5, 800);
    UploadSessionView begun =
        as(
            context,
            () ->
                uploads.begin(
                    "ITEM", UUID.randomUUID(), false, "PHOTO", file.length, context.userId()));
    as(context, () -> append(begun.id(), 0, Arrays.copyOfRange(file, 0, 200), context));

    // Backdated rather than waited for, which is what `OrphanedBlobSweepIT`
    // does with its grace period and for the same reason: the property under
    // test is what the sweep removes, not how long it takes to become due.
    as(
        context,
        () ->
            jdbc.sql("update media.upload_session set expires_at = ? where id = ?")
                .params(
                    java.sql.Timestamp.from(Instant.now().minus(java.time.Duration.ofHours(3))),
                    begun.id())
                .update());

    assertThat(sweep.sweep()).isPositive();

    assertThatThrownBy(() -> as(context, () -> uploads.status(begun.id())))
        .as("an upload nobody came back for is gone, and so are its bytes")
        .isInstanceOf(NotFoundException.class);
  }

  // ---------------------------------------------------------------------------

  private UploadSessionView append(UUID uploadId, long offset, byte[] chunk, Context context) {
    try {
      return uploads.append(uploadId, offset, new ByteArrayInputStream(chunk), context.userId());
    } catch (IOException unreadable) {
      throw new UncheckedIOException(unreadable);
    }
  }

  private Context tenant(String name) {
    UUID userId = createUser("resumable-" + name + "@example.org");
    return new Context(provisioning.provision("Resumable " + name, userId), userId);
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
                    "en",
                    passwordEncoder.encode(PASSWORD),
                    Instant.now())));
    enrolSecondFactor(userId);
    return userId;
  }

  private <T> T as(Context context, java.util.function.Supplier<T> body) {
    return TenantContext.callAs(
        context.tenantId(),
        () -> {
          java.util.concurrent.atomic.AtomicReference<T> result =
              new java.util.concurrent.atomic.AtomicReference<>();
          CallerContext.runAs(
              new CallerContext.Caller(context.userId(), context.tenantId(), "OWNER"),
              () -> result.set(transactions.execute(status -> body.get())));
          return result.get();
        });
  }

  /**
   * A text file of a given length, which this system stores unchanged.
   *
   * @param size how long
   * @return the bytes
   */
  private static byte[] text(int size) {
    byte[] file = new byte[size];
    for (int index = 0; index < size; index++) {
      // Printable ASCII and a newline every eighty characters: the detector
      // decides text by what the bytes are, and a run of identical characters
      // is as valid a text file as any but harder to read in a failure message.
      file[index] = (byte) (index % 80 == 79 ? 10 : 'a' + (index % 26));
    }
    return file;
  }

  /**
   * A file whose magic bytes say JPEG and whose content differs per index.
   *
   * @param index which one
   * @param size how long
   * @return the bytes
   */
  private static byte[] jpeg(int index, int size) {
    byte[] file = new byte[size];
    file[0] = (byte) 0xFF;
    file[1] = (byte) 0xD8;
    file[2] = (byte) 0xFF;
    file[3] = (byte) 0xE0;
    file[10] = (byte) index;
    return file;
  }

  /** Whose tenant and user. */
  private record Context(UUID tenantId, UUID userId) {}

  /** The two adapters an upload needs and this test has no use for. */
  @TestConfiguration(proxyBeanMethods = false)
  static class StubUploadDependencies {

    /**
     * Replaces the ClamAV adapter, which is proved elsewhere.
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
            byte[] original = Files.readAllBytes(source);
            Files.write(
                target,
                ("derived-at-" + maxEdge + "px-" + original.length + "-" + (int) original[10])
                    .getBytes(StandardCharsets.UTF_8));
          } catch (IOException unwritable) {
            throw new UncheckedIOException(unwritable);
          }
          return new Dimensions(maxEdge, maxEdge);
        }
      };
    }
  }
}
