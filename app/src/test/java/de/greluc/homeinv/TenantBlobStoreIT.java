/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.ByteString;
import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.media.api.BlobStore;
import de.greluc.homeinv.media.api.DeploymentBlobStore;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.plugin.v1.BlobStoreGrpc;
import de.greluc.homeinv.plugin.v1.DeleteRequest;
import de.greluc.homeinv.plugin.v1.DeleteResponse;
import de.greluc.homeinv.plugin.v1.GetRequest;
import de.greluc.homeinv.plugin.v1.GetResponse;
import de.greluc.homeinv.plugin.v1.HeadRequest;
import de.greluc.homeinv.plugin.v1.HeadResponse;
import de.greluc.homeinv.plugin.v1.PutRequest;
import de.greluc.homeinv.plugin.v1.PutResponse;
import de.greluc.homeinv.plugins.application.DefaultPluginRegistry;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import io.grpc.Grpc;
import io.grpc.Server;
import io.grpc.ServerCredentials;
import io.grpc.Status;
import io.grpc.TlsServerCredentials;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A tenant's bytes go to the store that tenant chose (REQ-MED-009, ADR-0026).
 *
 * <p>Against a <b>real</b> plugin over a real TLS socket, the way {@link DocumentRenderIT} proves
 * its port: what is asserted is the contract between the core and a storage plugin, and a mocked
 * stub would assert the mock.
 *
 * <p>Four properties, and the third is the one that would otherwise be found by a tenant:
 *
 * <ul>
 *   <li>a tenant that granted no storage plugin uses the deployment's store;
 *   <li>a tenant that granted one has its bytes written <b>there</b>, and the deployment's store
 *       never sees them;
 *   <li>a photograph uploaded <b>before</b> the grant is still readable after it — granting a store
 *       is not a migration, and without the fallback it would be a silent loss;
 *   <li>a deletion removes the blob from both, because it may live in either.
 * </ul>
 */
@DisplayName("A tenant's blob store")
class TenantBlobStoreIT extends AbstractIntegrationTest {

  private static final String PLUGIN = "de.greluc.homeinv.plugin.test.store";
  private static final String PASSWORD = "correct-horse-battery-staple-42";

  private static final AtomicReference<Server> SERVER = new AtomicReference<>();
  private static final AtomicReference<TestPki.Identity> IDENTITY = new AtomicReference<>();

  /** What the plugin holds, by tenant and address. The test reads it to see where bytes went. */
  private static final Map<String, byte[]> IN_THE_PLUGIN = new ConcurrentHashMap<>();

  @Autowired private BlobStore blobs;
  @Autowired private DeploymentBlobStore deployment;
  @Autowired private DefaultPluginRegistry registrations;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private AppUserRepository users;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;

  @AfterAll
  static void stopTheStore() {
    Server running = SERVER.getAndSet(null);
    if (running != null) {
      running.shutdownNow();
    }
    IN_THE_PLUGIN.clear();
  }

  @Test
  @DisplayName("is the deployment's when the tenant granted no plugin")
  void withoutAGrantTheDeploymentStoreIsUsed() {
    UUID tenant = aTenant("none");
    String address = "a".repeat(64);

    inTenant(tenant, () -> assertThat(blobs.store(tenant, address, bytes("kept here"))).isTrue());

    assertThat(deployment.exists(tenant, address)).isTrue();
    assertThat(IN_THE_PLUGIN).doesNotContainKey(key(tenant, address));
  }

  @Test
  @DisplayName("is the plugin's once granted, and the deployment's store never sees the bytes")
  void withAGrantTheBytesGoThere() throws Exception {
    UUID tenant = aTenant("granted");
    install(tenant);
    String address = "b".repeat(64);

    inTenant(
        tenant,
        () -> {
          assertThat(blobs.store(tenant, address, bytes("the tenant's own bucket"))).isTrue();
          assertThat(blobs.exists(tenant, address)).isTrue();
          assertThat(read(blobs.open(tenant, address))).isEqualTo("the tenant's own bucket");
        });

    assertThat(IN_THE_PLUGIN).containsKey(key(tenant, address));
    // Which is the point of granting a store at all: the photographs are where
    // the tenant chose, and this deployment holds no copy of them.
    assertThat(deployment.exists(tenant, address)).isFalse();
  }

  @Test
  @DisplayName("still reads what was stored before the plugin was granted")
  void whatCameBeforeIsStillReadable() throws Exception {
    UUID tenant = aTenant("before");
    String address = "c".repeat(64);

    inTenant(tenant, () -> blobs.store(tenant, address, bytes("uploaded last year")));

    install(tenant);

    inTenant(
        tenant,
        () -> {
          // Granting a store is not a migration. Without the fallback this
          // photograph would be gone and nothing would say so, which is the one
          // thing this system does not do.
          assertThat(blobs.exists(tenant, address)).isTrue();
          assertThat(read(blobs.open(tenant, address))).isEqualTo("uploaded last year");
        });
  }

  @Test
  @DisplayName("removes a blob from both places, because it may live in either")
  void deletingRemovesItFromBoth() throws Exception {
    UUID tenant = aTenant("delete");
    String older = "d".repeat(64);
    String newer = "e".repeat(64);

    inTenant(tenant, () -> blobs.store(tenant, older, bytes("before the grant")));
    install(tenant);

    inTenant(
        tenant,
        () -> {
          blobs.store(tenant, newer, bytes("after the grant"));
          blobs.delete(tenant, older);
          blobs.delete(tenant, newer);
        });

    // A deletion the tenant asked for leaves nothing behind in the other place
    // (REQ-PRIV-004). Half a deletion is the failure mode two stores invite.
    assertThat(deployment.exists(tenant, older)).isFalse();
    assertThat(deployment.exists(tenant, newer)).isFalse();
    assertThat(IN_THE_PLUGIN).doesNotContainKey(key(tenant, older));
    assertThat(IN_THE_PLUGIN).doesNotContainKey(key(tenant, newer));
  }

  // -------------------------------------------------------------------------

  /** A body that may throw, run with a tenant in context. */
  @FunctionalInterface
  private interface Body {
    void run() throws Exception;
  }

  /**
   * Runs a body with the tenant in context, failing the test on anything it throws.
   *
   * @param tenantId who the calls are for
   * @param body what to run
   */
  private static void inTenant(UUID tenantId, Body body) {
    TenantContext.runAs(
        tenantId,
        () -> {
          try {
            body.run();
          } catch (Exception failed) {
            throw new AssertionError("The call failed", failed);
          }
        });
  }

  private static ByteArrayInputStream bytes(String content) {
    return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
  }

  private static String read(InputStream stream) throws IOException {
    try (stream) {
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static String key(UUID tenantId, String sha256) {
    return tenantId + "/" + sha256;
  }

  /**
   * Installs the store plugin and lets this tenant use it.
   *
   * @param tenantId who is granting
   * @throws Exception when the plugin cannot be started or registered
   */
  private void install(UUID tenantId) throws Exception {
    int port = startStore();
    registrations.register(
        manifest().getBytes(StandardCharsets.UTF_8),
        "localhost:" + port,
        identity().fingerprint(),
        true);
    TenantContext.runAs(
        tenantId, () -> registrations.grant(PLUGIN, "network:outbound", UUID.randomUUID()));
  }

  /**
   * Starts the store, once for the class.
   *
   * @return the port it listens on
   * @throws Exception when the socket cannot be opened
   */
  private static int startStore() throws Exception {
    Server running = SERVER.get();
    if (running != null) {
      return running.getPort();
    }
    byte[] bundle = Files.readAllBytes(identity().bundle());
    ServerCredentials credentials =
        TlsServerCredentials.newBuilder()
            .keyManager(new ByteArrayInputStream(bundle), new ByteArrayInputStream(bundle))
            .trustManager(new ByteArrayInputStream(PKI.caPem().getBytes(StandardCharsets.UTF_8)))
            .clientAuth(TlsServerCredentials.ClientAuth.REQUIRE)
            .build();
    Server server =
        Grpc.newServerBuilderForPort(0, credentials).addService(new Store()).build().start();
    SERVER.set(server);
    return server.getPort();
  }

  /** A store that keeps what it is given in a map, which is all this test needs of one. */
  private static final class Store extends BlobStoreGrpc.BlobStoreImplBase {

    @Override
    public StreamObserver<PutRequest> put(StreamObserver<PutResponse> responses) {
      return new StreamObserver<>() {
        private String address;
        private final ByteArrayOutputStream content = new ByteArrayOutputStream();

        @Override
        public void onNext(PutRequest part) {
          if (part.hasBlob()) {
            address = addressOf(part.getBlob().getTenantId(), part.getBlob().getSha256());
          } else {
            content.writeBytes(part.getChunk().toByteArray());
          }
        }

        @Override
        public void onError(Throwable error) {
          // The core hung up; a test store has nothing to undo.
        }

        @Override
        public void onCompleted() {
          byte[] written = content.toByteArray();
          boolean created = IN_THE_PLUGIN.putIfAbsent(address, written) == null;
          responses.onNext(
              PutResponse.newBuilder().setCreated(created).setByteSize(written.length).build());
          responses.onCompleted();
        }
      };
    }

    @Override
    public void get(GetRequest request, StreamObserver<GetResponse> responses) {
      byte[] content = held(request.getBlob().getTenantId(), request.getBlob().getSha256());
      if (content == null) {
        responses.onError(Status.NOT_FOUND.withDescription("No such blob").asRuntimeException());
        return;
      }
      responses.onNext(GetResponse.newBuilder().setChunk(ByteString.copyFrom(content)).build());
      responses.onCompleted();
    }

    @Override
    public void head(HeadRequest request, StreamObserver<HeadResponse> responses) {
      byte[] content = held(request.getBlob().getTenantId(), request.getBlob().getSha256());
      responses.onNext(
          HeadResponse.newBuilder()
              .setExists(content != null)
              .setByteSize(content == null ? 0 : content.length)
              .build());
      responses.onCompleted();
    }

    @Override
    public void delete(DeleteRequest request, StreamObserver<DeleteResponse> responses) {
      boolean removed =
          IN_THE_PLUGIN.remove(
                  addressOf(request.getBlob().getTenantId(), request.getBlob().getSha256()))
              != null;
      responses.onNext(DeleteResponse.newBuilder().setRemoved(removed).build());
      responses.onCompleted();
    }

    private static byte[] held(String tenantId, String sha256) {
      return IN_THE_PLUGIN.get(addressOf(tenantId, sha256));
    }

    private static String addressOf(String tenantId, String sha256) {
      return tenantId + "/" + sha256;
    }
  }

  /** The store's identity, issued once for the class. */
  private static TestPki.Identity identity() throws Exception {
    if (IDENTITY.get() == null) {
      IDENTITY.compareAndSet(null, PKI.issue("localhost"));
    }
    return IDENTITY.get();
  }

  private static String manifest() {
    return """
        apiVersion: home-inv.plugin/v1
        metadata:
          id: %s
          name: "A store that keeps things"
          version: "1.0.0"
          vendor: "greluc"
          license: "Apache-2.0"
        spec:
          contract: ">=1.0.0 <2.0.0"
          runtime: out-of-process
          implements:
            - port: BlobStore
              priority: 100
          capabilities:
            - id: network:outbound
              tcp: ["objects.example.org:443"]
              reason: "Putting the bytes where the tenant keeps them"
        """
        .formatted(PLUGIN);
  }

  private UUID aTenant(String name) {
    UUID userId = UUID.randomUUID();
    transactions.executeWithoutResult(
        status ->
            users.save(
                AppUser.create(
                    userId,
                    "blob-" + name + "-" + UUID.randomUUID() + "@example.org",
                    "Keeper",
                    "en",
                    passwordEncoder.encode(PASSWORD),
                    Instant.now())));
    return provisioning.provision("Tenant " + name, userId);
  }
}
