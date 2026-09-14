/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import de.greluc.homeinv.platform.Page;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.media.api.ImageProcessor;
import de.greluc.homeinv.media.api.MediaService;
import de.greluc.homeinv.media.api.MediaView;
import de.greluc.homeinv.media.api.VirusScanner;
import de.greluc.homeinv.platform.CallerContext;
import de.greluc.homeinv.platform.InvalidCursorException;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
 * The attachments of one thing are bounded, paged and know which one is primary.
 *
 * <h2>Why a list this short is paged at all</h2>
 *
 * <p>Because {@code REQ-NFR-010} admits no endpoint that loads a collection without a bound, and
 * "this one is usually small" is the reasoning behind every unbounded query that ever took a server
 * down. Nothing stops a client attaching a thousand photographs to one box, and the endpoint that
 * returned all of them returned them in one list with one signed URL generated per row.
 *
 * <p>{@code REQ-MED-002}'s second half is here too: the primary image is <em>selectable</em>, which
 * it already was, and the first one is the <em>default</em>, which it was not — an upload that did
 * not ask to be primary left the thing with no primary image at all.
 */
@DisplayName("The attachments of a thing")
@Import(MediaListingIT.StubUploadDependencies.class)
class MediaListingIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple-42";

  @Autowired private MediaService media;
  @Autowired private AppUserRepository users;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;

  @Test
  @DisplayName("are paged by cursor, never all at once (REQ-NFR-010, REQ-SRCH-009)")
  void theListingIsPaged() {
    Context context = tenant("paging");
    UUID item = UUID.randomUUID();
    List<UUID> uploaded = upload(context, item, 5);

    Page<MediaView> first = as(context, () -> media.attachmentsOf("ITEM", item, null, 2));
    assertThat(first.data()).hasSize(2);
    assertThat(first.nextCursor()).isNotNull();

    Page<MediaView> second =
        as(context, () -> media.attachmentsOf("ITEM", item, first.nextCursor(), 2));
    assertThat(second.data()).hasSize(2);

    Page<MediaView> third =
        as(context, () -> media.attachmentsOf("ITEM", item, second.nextCursor(), 2));
    assertThat(third.data()).hasSize(1);
    // A short page is the last one, and says so rather than handing out a cursor
    // a client would spend a request discovering is empty.
    assertThat(third.nextCursor()).isNull();

    List<UUID> paged = new ArrayList<>();
    first.data().forEach(view -> paged.add(view.id()));
    second.data().forEach(view -> paged.add(view.id()));
    third.data().forEach(view -> paged.add(view.id()));
    assertThat(paged)
        .as("every attachment appears exactly once across the pages")
        .containsExactlyInAnyOrderElementsOf(uploaded);
  }

  @Test
  @DisplayName("cap a page at 200 however many the caller asks for (REQ-NFR-010)")
  void theCapHolds() {
    Context context = tenant("cap");
    UUID item = UUID.randomUUID();
    upload(context, item, 3);

    // The service clamps; the endpoint refuses a larger number outright with its
    // `@Max(200)`, and both matter — a caller reaching the service another way
    // must not be able to ask for everything either.
    Page<MediaView> page =
        as(context, () -> media.attachmentsOf("ITEM", item, null, Integer.MAX_VALUE));
    assertThat(page.data()).hasSize(3);
    assertThat(page.nextCursor()).isNull();
  }

  @Test
  @DisplayName("refuse a cursor that belongs to a different thing (REQ-SRCH-009)")
  void aForeignCursorIsRefused() {
    Context context = tenant("foreign-cursor");
    UUID item = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    upload(context, item, 2);
    upload(context, other, 2);

    Page<MediaView> page = as(context, () -> media.attachmentsOf("ITEM", item, null, 1));
    assertThat(page.nextCursor()).isNotNull();

    assertThatThrownBy(
            () -> as(context, () -> media.attachmentsOf("ITEM", other, page.nextCursor(), 1)))
        .as("a cursor resumed against another target would silently return the wrong rows")
        .isInstanceOf(InvalidCursorException.class);
  }

  @Test
  @DisplayName("make the first upload the primary image without being asked (REQ-MED-002)")
  void theFirstUploadIsPrimary() {
    Context context = tenant("primary");
    UUID item = UUID.randomUUID();
    upload(context, item, 3);

    Page<MediaView> page = as(context, () -> media.attachmentsOf("ITEM", item, null, 10));

    List<MediaView> primary = page.data().stream().filter(MediaView::primaryImage).toList();
    assertThat(primary).as("exactly one attachment is the primary image").hasSize(1);
    assertThat(primary.getFirst().id())
        .as("and it is the first one uploaded")
        .isEqualTo(page.data().getFirst().id());
  }

  // -------------------------------------------------------------------------

  private record Context(UUID tenantId, UUID userId) {}

  private Context tenant(String name) {
    UUID userId = createUser("media-listing-" + name + "@example.org");
    return new Context(provisioning.provision("Media listing " + name, userId), userId);
  }

  /**
   * Attaches {@code count} distinguishable files to one target.
   *
   * @param context whose tenant and user
   * @param targetId what to attach them to
   * @param count how many
   * @return their media object ids, in upload order
   */
  private List<UUID> upload(Context context, UUID targetId, int count) {
    List<UUID> ids = new ArrayList<>();
    for (int index = 0; index < count; index++) {
      byte[] file = jpeg(index);
      ids.add(
          as(
              context,
              () -> {
                try {
                  return media
                      .upload(
                          new ByteArrayInputStream(file),
                          "ITEM",
                          targetId,
                          false,
                          context.userId())
                      .id();
                } catch (IOException unreadable) {
                  throw new UncheckedIOException(unreadable);
                }
              }));
    }
    return ids;
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
    // REQ-AUTH-003: an OWNER or ADMIN with no second factor is refused every
    // request in the tenant. The enrolment loop is proved in SecondFactorIT;
    // here it is a precondition rather than the subject.
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
   * A file whose magic bytes say JPEG and whose content differs per index.
   *
   * <p>Different content matters: blobs are content-addressed within a tenant, so five identical
   * uploads would be one object with one attachment and would prove nothing about paging.
   *
   * @param index which one
   * @return the bytes
   */
  private static byte[] jpeg(int index) {
    byte[] file = new byte[256];
    file[0] = (byte) 0xFF;
    file[1] = (byte) 0xD8;
    file[2] = (byte) 0xFF;
    file[3] = (byte) 0xE0;
    file[10] = (byte) index;
    return file;
  }

  /** The two adapters an upload needs and this test has no use for. */
  @TestConfiguration(proxyBeanMethods = false)
  static class StubUploadDependencies {

    /**
     * Replaces the ClamAV adapter.
     *
     * <p>The scan is fail-closed and there is no scanner here, which is the design working. That
     * behaviour is tested in {@code UploadPipelineTest} and against real ClamAV in the smoke suite;
     * a 1 GB signature database on every machine that runs this test would buy nothing it proves.
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
            // The source bytes go into the derivative, so two different uploads
            // keep two different content addresses after re-encoding.
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
