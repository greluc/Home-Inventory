/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.homeinv.media.infrastructure.BlobStoreChannelFactory;
import de.greluc.homeinv.media.infrastructure.GrpcBlobStore;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.MountableFile;

/**
 * The `BlobStore` contract, against the implementation that defines it.
 *
 * <p>ADR-0043 makes the in-deployment store the reference every other {@code BlobStore} is compared
 * to — so this test runs the actual Rust service, built from {@code blobstore/Dockerfile}, and
 * drives it through the same client the application uses. Nothing here is stubbed: the mTLS
 * handshake happens, the certificate pin is checked, and the server verifies the digest.
 *
 * <p>It is the only test that pays for that container. The rest of the suite uses
 * {@link TestBlobStore}, because "put then get returns the same bytes" needs no process to
 * establish and the properties that do need one are all established here.
 *
 * <h2>The build context is assembled rather than pointed at</h2>
 *
 * <p>The repository root is about a gigabyte — {@code blobstore/target} alone is 800 MB of
 * incremental Rust artefacts — and Testcontainers tars whatever directory it is given, ignoring
 * {@code .dockerignore}. So the four files and two directories the image actually needs are copied
 * into a temporary root, which takes milliseconds instead of minutes.
 */
@DisplayName("The blob store contract")
class BlobStoreContractIT {

  private static final UUID TENANT = UUID.randomUUID();

  private static TestPki pki;
  private static TestPki.Identity serverIdentity;
  private static TestPki.Identity clientIdentity;
  private static Path context;
  private static GenericContainer<?> blobstore;
  private static GrpcBlobStore store;

  @BeforeAll
  static void startTheRealService() throws Exception {
    pki = TestPki.create();
    serverIdentity = pki.issue("blobstore");
    clientIdentity = pki.issue("api");

    context = assembleBuildContext();

    blobstore =
        new GenericContainer<>(
                new ImageFromDockerfile("home-inv-blobstore-contract", false)
                    // The whole assembled tree as the context, with the Dockerfile
                    // at its root: `withDockerfile` alone would make the
                    // Dockerfile's own directory the context, and every `COPY
                    // blobstore/...` in it would then be looking one level too
                    // deep.
                    .withFileFromPath(".", context))
            .withExposedPorts(8100)
            .withCopyFileToContainer(
                MountableFile.forHostPath(serverIdentity.bundle()), "/run/secrets/mtls-blobstore")
            .waitingFor(Wait.forListeningPort());
    blobstore.start();

    store =
        new GrpcBlobStore(
            new BlobStoreChannelFactory(
                blobstore.getHost() + ":" + blobstore.getMappedPort(8100),
                serverIdentity.fingerprint(),
                clientIdentity.bundle().toString(),
                // The container answers on localhost and its certificate says
                // `blobstore`. gRPC verifies the name as well as the pin, and the
                // two answer different questions.
                "blobstore"));
  }

  @AfterAll
  static void stop() throws IOException {
    if (store != null) {
      store.close();
    }
    if (blobstore != null) {
      blobstore.stop();
    }
    if (context != null) {
      deleteRecursively(context);
    }
  }

  @Test
  @DisplayName("stores bytes and returns exactly them")
  void roundTrip() throws IOException {
    byte[] content = "a photograph, as far as this test is concerned".getBytes(StandardCharsets.UTF_8);
    String digest = sha256(content);

    assertThat(store.store(TENANT, digest, new ByteArrayInputStream(content))).isTrue();

    try (InputStream read = store.open(TENANT, digest)) {
      assertThat(read.readAllBytes()).isEqualTo(content);
    }
    assertThat(store.exists(TENANT, digest)).isTrue();
  }

  @Test
  @DisplayName("stores a duplicate once, and says so (REQ-MED-007)")
  void duplicatesAreStoredOnce() throws IOException {
    byte[] content = "the same file twice".getBytes(StandardCharsets.UTF_8);
    String digest = sha256(content);

    assertThat(store.store(TENANT, digest, new ByteArrayInputStream(content))).isTrue();
    // False means "already there". The caller uses it to skip work, and a store
    // that always said true would make the same photograph cost a second copy.
    assertThat(store.store(TENANT, digest, new ByteArrayInputStream(content))).isFalse();
  }

  @Test
  @DisplayName("never deduplicates across tenants (REQ-MED-007, ADR-0032)")
  void tenantsDoNotShareBlobs() throws IOException {
    byte[] content = "bytes two tenants happen to share".getBytes(StandardCharsets.UTF_8);
    String digest = sha256(content);
    UUID otherTenant = UUID.randomUUID();

    assertThat(store.store(TENANT, digest, new ByteArrayInputStream(content))).isTrue();
    // A global namespace would have made "do you have this file" answerable
    // across the tenant boundary, and would have let the second tenant inherit
    // the first one's malware verdict.
    assertThat(store.exists(otherTenant, digest)).isFalse();
    assertThat(store.store(otherTenant, digest, new ByteArrayInputStream(content))).isTrue();
  }

  @Test
  @DisplayName("refuses content that does not hash to the address given")
  void theServerVerifiesTheDigest() {
    byte[] content = "these bytes".getBytes(StandardCharsets.UTF_8);
    String wrongDigest = sha256("some other bytes".getBytes(StandardCharsets.UTF_8));

    // Believing the caller's hash would make this content-addressed in name
    // only, and the first corrupted transfer would be indistinguishable from a
    // different file.
    assertThatThrownBy(() -> store.store(TENANT, wrongDigest, new ByteArrayInputStream(content)))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("does not hash");
  }

  @Test
  @DisplayName("reports a missing blob as missing rather than as empty")
  void aMissingBlobIsNotFound() {
    String absent = sha256("never stored".getBytes(StandardCharsets.UTF_8));

    assertThat(store.exists(TENANT, absent)).isFalse();
    assertThatThrownBy(() -> store.open(TENANT, absent))
        .isInstanceOf(java.io.FileNotFoundException.class);
  }

  @Test
  @DisplayName("deletes, and deleting again is not an error")
  void deletionIsIdempotent() throws IOException {
    byte[] content = "to be removed".getBytes(StandardCharsets.UTF_8);
    String digest = sha256(content);

    store.store(TENANT, digest, new ByteArrayInputStream(content));
    store.delete(TENANT, digest);
    assertThat(store.exists(TENANT, digest)).isFalse();

    // Deletion is retried after a partial failure, and a retry that failed
    // because the work was already done would be a retry nobody could make
    // succeed.
    store.delete(TENANT, digest);
  }

  @Test
  @DisplayName("refuses a client whose certificate the server does not trust")
  void anUntrustedClientIsRefused() throws Exception {
    // A second authority: a certificate that is perfectly valid and signed by
    // somebody else. This is the case ADR-0044 is about — reachability on the
    // internal segment is not authorisation.
    TestPki stranger = TestPki.create();
    TestPki.Identity strangerIdentity = stranger.issue("api");

    GrpcBlobStore impostor =
        new GrpcBlobStore(
            new BlobStoreChannelFactory(
                blobstore.getHost() + ":" + blobstore.getMappedPort(8100),
                serverIdentity.fingerprint(),
                strangerIdentity.bundle().toString(),
                "blobstore"));
    try {
      byte[] content = "should never be stored".getBytes(StandardCharsets.UTF_8);
      assertThatThrownBy(
              () -> impostor.store(TENANT, sha256(content), new ByteArrayInputStream(content)))
          .isInstanceOf(IOException.class);
    } finally {
      impostor.close();
    }
  }

  @Test
  @DisplayName("refuses a server whose certificate does not match the pin")
  void aWrongPinIsRefused() throws Exception {
    // The same running service, a different expected fingerprint. Without the
    // pin, any holder of a deployment certificate could stand up a service on
    // the segment and receive every tenant's media (REQ-SEC-056).
    String someoneElsesFingerprint = pki.issue("impostor").fingerprint();

    GrpcBlobStore pickyClient =
        new GrpcBlobStore(
            new BlobStoreChannelFactory(
                blobstore.getHost() + ":" + blobstore.getMappedPort(8100),
                someoneElsesFingerprint,
                clientIdentity.bundle().toString(),
                "blobstore"));
    try {
      byte[] content = "should never be stored either".getBytes(StandardCharsets.UTF_8);
      assertThatThrownBy(
              () -> pickyClient.store(TENANT, sha256(content), new ByteArrayInputStream(content)))
          .isInstanceOf(IOException.class);
    } finally {
      pickyClient.close();
    }
  }

  // -------------------------------------------------------------------------

  /**
   * Copies the few files the image needs into a temporary root.
   *
   * @return the context root
   * @throws IOException when copying fails
   */
  private static Path assembleBuildContext() throws IOException {
    Path root = Files.createTempDirectory("homeinv-blobstore-context-");
    Path repository = Path.of("..").toAbsolutePath().normalize();

    copyTree(repository.resolve("proto"), root.resolve("proto"));
    Files.createDirectories(root.resolve("blobstore"));
    for (String file : new String[] {"Cargo.toml", "Cargo.lock", "build.rs", "Dockerfile"}) {
      Files.copy(repository.resolve("blobstore").resolve(file), root.resolve("blobstore").resolve(file));
    }
    copyTree(repository.resolve("blobstore/src"), root.resolve("blobstore/src"));
    // Also at the context root, which is where the image builder looks for it.
    Files.copy(repository.resolve("blobstore/Dockerfile"), root.resolve("Dockerfile"));
    return root;
  }

  private static void copyTree(Path from, Path to) throws IOException {
    Files.walkFileTree(
        from,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
              throws IOException {
            Files.createDirectories(to.resolve(from.relativize(directory).toString()));
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
              throws IOException {
            Files.copy(file, to.resolve(from.relativize(file).toString()));
            return FileVisitResult.CONTINUE;
          }
        });
  }

  private static void deleteRecursively(Path root) throws IOException {
    if (!Files.exists(root)) {
      return;
    }
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
              throws IOException {
            Files.delete(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path directory, IOException failure)
              throws IOException {
            Files.delete(directory);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  private static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (Exception impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }
}
