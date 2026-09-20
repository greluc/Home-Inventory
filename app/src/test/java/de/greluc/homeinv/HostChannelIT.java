/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.google.protobuf.ByteString;
import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.plugin.v1.Block;
import de.greluc.homeinv.plugin.v1.CallContext;
import de.greluc.homeinv.plugin.v1.DocumentHeader;
import de.greluc.homeinv.plugin.v1.DocumentRendererFormatsRequest;
import de.greluc.homeinv.plugin.v1.DocumentRendererFormatsResponse;
import de.greluc.homeinv.plugin.v1.DocumentRendererGrpc;
import de.greluc.homeinv.plugin.v1.DocumentRendererRenderRequest;
import de.greluc.homeinv.plugin.v1.DocumentRendererRenderResponse;
import de.greluc.homeinv.plugin.v1.Heading;
import de.greluc.homeinv.plugin.v1.HostServicesGrpc;
import de.greluc.homeinv.plugin.v1.RenderedHeader;
import de.greluc.homeinv.plugins.application.DefaultPluginRegistry;
import de.greluc.homeinv.plugins.infrastructure.HostChannelServer;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import io.grpc.ChannelCredentials;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerCredentials;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.TlsChannelCredentials;
import io.grpc.TlsServerCredentials;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The one call that runs the other way round (ADR-0071, REQ-PLG-016).
 *
 * <p>A plugin describes a document and asks the <b>core</b> to render it; the core renders it with
 * whichever {@code DocumentRenderer} the tenant has installed — a different plugin, which the
 * caller never addresses and never learns the name of. What is asserted here is the three gates,
 * because this channel reverses a constraint ADR-0037 set deliberately and its whole defence is
 * that it is narrow:
 *
 * <ol>
 *   <li>a certificate no registration pins does not get a connection at all;
 *   <li>a plugin without {@code host:render-document} is refused, and a grant in one tenant is not
 *       a grant in another;
 *   <li>a missing grant and a missing renderer are answered with the <b>same words</b>, so nobody
 *       can learn what a tenant consented to by asking.
 * </ol>
 *
 * <p>Over a real socket with real certificates, like {@code PluginRuntimeIT} and {@code
 * DocumentRenderIT}: the subject is mutual authentication with a pinned certificate, and a test
 * that stubbed the transport would be testing the stub.
 */
@DisplayName("The host channel")
class HostChannelIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple-42";
  private static final String PLUGIN = "de.greluc.homeinv.plugin.test.host.";

  /**
   * Where the channel listens for this run.
   *
   * <p>Chosen before the context starts, because {@code HOMEINV_PLUGIN_HOST_PORT} is read at
   * startup and zero means the channel is off — which is the production default, and the reason
   * this class has a context of its own.
   */
  private static final int HOST_PORT = freePort();

  private static final AtomicReference<Server> RENDERER = new AtomicReference<>();
  private static final AtomicReference<TestPki.Identity> RENDERER_IDENTITY =
      new AtomicReference<>();
  private static final AtomicReference<TestPki.Identity> CALLER_IDENTITY = new AtomicReference<>();

  @Autowired private HostChannelServer host;
  @Autowired private DefaultPluginRegistry registrations;
  @Autowired private AppUserRepository users;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;

  /**
   * Turns the channel on for this class.
   *
   * @param registry the registry Spring fills the properties from
   */
  @DynamicPropertySource
  static void hostChannel(DynamicPropertyRegistry registry) {
    registry.add("HOMEINV_PLUGIN_HOST_PORT", () -> HOST_PORT);
  }

  @AfterAll
  static void stopTheRenderer() {
    Server running = RENDERER.getAndSet(null);
    if (running != null) {
      running.shutdownNow();
    }
  }

  @Test
  @DisplayName("renders for a granted plugin, through a renderer that plugin never names")
  void aGrantedPluginGetsItsDocument() throws Exception {
    assertThat(host.port()).as("the channel is listening").isEqualTo(HOST_PORT);

    List<Block> drawn = new ArrayList<>();
    int rendererPort = startRenderer(drawn);
    UUID tenant = aTenant("granted");

    installRenderer("one", rendererPort, tenant);
    String caller = installCaller("one", rendererPort);
    TenantContext.runAs(
        tenant, () -> registrations.grant(caller, "host:render-document", UUID.randomUUID()));

    Rendered answer = askForADocument(tenant, callerIdentity());

    assertThat(answer.mediaType()).isEqualTo("application/pdf");
    assertThat(new String(answer.content(), StandardCharsets.UTF_8))
        .isEqualTo("%PDF- rendered for a plugin");

    // What the caller described reached the renderer unchanged. The core chooses
    // the recipient and reads nothing on the way.
    assertThat(drawn.stream().filter(Block::hasHeading).map(block -> block.getHeading().getText()))
        .containsExactly("Written by a plugin");
  }

  @Test
  @DisplayName("refuses a plugin without the grant in the same words as one with no renderer")
  void theTwoRefusalsAreOneAnswer() throws Exception {
    int rendererPort = startRenderer(new ArrayList<>());

    // A tenant that has a renderer, called by a plugin that was never granted
    // the capability.
    UUID withRenderer = aTenant("ungranted");
    installRenderer("two", rendererPort, withRenderer);
    installCaller("two", rendererPort);
    Status ungranted = refusalFor(withRenderer, callerIdentity());

    // A tenant with no renderer at all, called by a plugin that holds the grant.
    UUID withoutRenderer = aTenant("norenderer");
    String caller = installCaller("three", rendererPort);
    TenantContext.runAs(
        withoutRenderer,
        () -> registrations.grant(caller, "host:render-document", UUID.randomUUID()));
    Status noRenderer = refusalFor(withoutRenderer, callerIdentity());

    assertThat(ungranted.getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(noRenderer.getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
    // The point of the test: a caller cannot tell the two apart, so it cannot
    // enumerate what a tenant has consented to, one probe at a time (ADR-0071).
    assertThat(noRenderer.getDescription()).isEqualTo(ungranted.getDescription());
    assertThat(ungranted.getDescription()).contains("Either none is installed");
  }

  @Test
  @DisplayName("does not carry a grant from one tenant into another")
  void aGrantIsPerTenant() throws Exception {
    int rendererPort = startRenderer(new ArrayList<>());
    UUID granted = aTenant("tenant-a");
    UUID other = aTenant("tenant-b");

    // Both tenants have the same renderer installed; only one granted the caller.
    installRenderer("four", rendererPort, granted);
    installRenderer("four", rendererPort, other);
    String caller = installCaller("four", rendererPort);
    TenantContext.runAs(
        granted, () -> registrations.grant(caller, "host:render-document", UUID.randomUUID()));

    assertThat(askForADocument(granted, callerIdentity()).mediaType()).isEqualTo("application/pdf");
    assertThat(refusalFor(other, callerIdentity()).getCode())
        .isEqualTo(Status.Code.FAILED_PRECONDITION);
  }

  @Test
  @DisplayName("gives no connection at all to a certificate no registration pins")
  void anUnpinnedCertificateNeverGetsIn() throws Exception {
    UUID tenant = aTenant("stranger");
    // Signed by the deployment's own CA, so a valid member of the deployment --
    // and not a plugin. Trusting the CA alone is exactly what would let it in
    // (ADR-0044).
    TestPki.Identity stranger = PKI.issue("a-neighbour");

    Status refused = refusalFor(tenant, stranger);

    // Refused by the transport, before a method is reached. Which code a failed
    // handshake surfaces as depends on the platform; what matters is that it is
    // not an application status, because no application code ran.
    assertThat(refused.getCode())
        .isIn(Status.Code.UNAVAILABLE, Status.Code.UNKNOWN, Status.Code.INTERNAL);
  }

  // -------------------------------------------------------------------------

  /**
   * Calls the host channel the way a plugin would.
   *
   * @param tenant the tenant the document is for
   * @param identity the certificate the caller presents
   * @return what came back
   * @throws StatusRuntimeException when the call was refused
   * @throws Exception when the channel cannot be built
   */
  private Rendered askForADocument(UUID tenant, TestPki.Identity identity) throws Exception {
    byte[] bundle = Files.readAllBytes(identity.bundle());
    ChannelCredentials credentials =
        TlsChannelCredentials.newBuilder()
            .keyManager(new ByteArrayInputStream(bundle), new ByteArrayInputStream(bundle))
            .trustManager(new ByteArrayInputStream(PKI.caPem().getBytes(StandardCharsets.UTF_8)))
            .build();
    ManagedChannel channel =
        Grpc.newChannelBuilder("localhost:" + host.port(), credentials)
            // The core's own certificate names `api`, which is what it is called
            // inside the deployment. A test dialling localhost has to say which
            // name it expects, or it would be asserting that the core forgot to
            // present one.
            .overrideAuthority("api")
            .build();
    try {
      ByteArrayOutputStream content = new ByteArrayOutputStream();
      AtomicReference<String> mediaType = new AtomicReference<>();
      AtomicReference<Throwable> failed = new AtomicReference<>();
      CountDownLatch done = new CountDownLatch(1);

      StreamObserver<DocumentRendererRenderRequest> requests =
          HostServicesGrpc.newStub(channel)
              .withDeadlineAfter(30, TimeUnit.SECONDS)
              .renderDocument(
                  new StreamObserver<DocumentRendererRenderResponse>() {
                    @Override
                    public void onNext(DocumentRendererRenderResponse part) {
                      if (part.hasHeader()) {
                        mediaType.set(part.getHeader().getMediaType());
                      } else {
                        content.writeBytes(part.getContent().toByteArray());
                      }
                    }

                    @Override
                    public void onError(Throwable error) {
                      failed.set(error);
                      done.countDown();
                    }

                    @Override
                    public void onCompleted() {
                      done.countDown();
                    }
                  });

      requests.onNext(
          DocumentRendererRenderRequest.newBuilder()
              .setHeader(
                  DocumentHeader.newBuilder()
                      .setContext(
                          CallContext.newBuilder()
                              .setTenantId(tenant.toString())
                              .setLanguage("en")
                              .build())
                      .setTitle("A document a plugin wanted")
                      .setMediaType("application/pdf")
                      .build())
              .build());
      requests.onNext(
          DocumentRendererRenderRequest.newBuilder()
              .setBlock(
                  Block.newBuilder()
                      .setHeading(Heading.newBuilder().setLevel(1).setText("Written by a plugin")))
              .build());
      requests.onCompleted();

      assertThat(done.await(40, TimeUnit.SECONDS)).as("the call finished").isTrue();
      if (failed.get() instanceof StatusRuntimeException refused) {
        throw refused;
      }
      if (failed.get() != null) {
        throw new AssertionError("The call failed in an unexpected way", failed.get());
      }
      return new Rendered(content.toByteArray(), mediaType.get());
    } finally {
      channel.shutdownNow();
    }
  }

  /**
   * The status a refused call came back with.
   *
   * @param tenant the tenant named in the call
   * @param identity the certificate the caller presents
   * @return the status
   * @throws Exception when the channel cannot be built
   */
  private Status refusalFor(UUID tenant, TestPki.Identity identity) throws Exception {
    try {
      Rendered unexpected = askForADocument(tenant, identity);
      return fail(
          "The call was answered with "
              + unexpected.content().length
              + " bytes of "
              + unexpected.mediaType()
              + " instead of being refused");
    } catch (StatusRuntimeException refused) {
      return refused.getStatus();
    }
  }

  /**
   * Installs a renderer and lets one tenant use it.
   *
   * @param name what to call it, so that each test has its own
   * @param port where it listens
   * @param tenant the tenant consenting to it
   * @throws Exception when the manifest cannot be registered
   */
  private void installRenderer(String name, int port, UUID tenant) throws Exception {
    String pluginId = PLUGIN + "renderer." + name;
    registrations.register(
        rendererManifest(pluginId).getBytes(StandardCharsets.UTF_8),
        "localhost:" + port,
        rendererIdentity().fingerprint(),
        true);
    TenantContext.runAs(
        tenant, () -> registrations.grant(pluginId, "network:outbound", UUID.randomUUID()));
  }

  /**
   * Installs the plugin that does the calling, granted nothing yet.
   *
   * <p>Its endpoint is the renderer's, and is never dialled: the core answers this one, it does not
   * call it. What the registration is for is the <b>fingerprint</b>, which is how the channel
   * recognises the caller.
   *
   * @param name what to call it
   * @param port an endpoint the registration needs and nothing uses
   * @return its plugin id
   * @throws Exception when the manifest cannot be registered
   */
  private String installCaller(String name, int port) throws Exception {
    String pluginId = PLUGIN + "caller." + name;
    registrations.register(
        callerManifest(pluginId).getBytes(StandardCharsets.UTF_8),
        "localhost:" + port,
        callerIdentity().fingerprint(),
        true);
    return pluginId;
  }

  private static String rendererManifest(String pluginId) {
    return """
        apiVersion: home-inv.plugin/v1
        metadata:
          id: %s
          name: "A renderer that answers"
          version: "1.0.0"
          vendor: "greluc"
          license: "Apache-2.0"
        spec:
          contract: ">=1.0.0 <2.0.0"
          runtime: out-of-process
          implements:
            - port: DocumentRenderer
              priority: 100
          capabilities:
            - id: network:outbound
              tcp: ["fonts.example.org:443"]
              reason: "Fetching the fonts it draws with"
        """
        .formatted(pluginId);
  }

  private static String callerManifest(String pluginId) {
    return """
        apiVersion: home-inv.plugin/v1
        metadata:
          id: %s
          name: "A plugin that writes documents"
          version: "1.0.0"
          vendor: "greluc"
          license: "Apache-2.0"
        spec:
          contract: ">=1.0.0 <2.0.0"
          runtime: out-of-process
          implements:
            - port: MetadataResolver
              priority: 100
          capabilities:
            - id: host:render-document
              reason: "Turning what it found into something a person can read"
        """
        .formatted(pluginId);
  }

  /**
   * Starts a renderer that answers anything with the same bytes.
   *
   * @param drawn where the blocks it was asked to draw are collected
   * @return its port
   * @throws Exception when the socket cannot be opened
   */
  private static int startRenderer(List<Block> drawn) throws Exception {
    stopTheRenderer();
    byte[] bundle = Files.readAllBytes(rendererIdentity().bundle());
    ServerCredentials credentials =
        TlsServerCredentials.newBuilder()
            .keyManager(new ByteArrayInputStream(bundle), new ByteArrayInputStream(bundle))
            .trustManager(new ByteArrayInputStream(PKI.caPem().getBytes(StandardCharsets.UTF_8)))
            .clientAuth(TlsServerCredentials.ClientAuth.REQUIRE)
            .build();
    Server server =
        Grpc.newServerBuilderForPort(0, credentials)
            .addService(
                new DocumentRendererGrpc.DocumentRendererImplBase() {

                  @Override
                  public void outputFormats(
                      DocumentRendererFormatsRequest request,
                      StreamObserver<DocumentRendererFormatsResponse> observer) {
                    observer.onNext(
                        DocumentRendererFormatsResponse.newBuilder()
                            .addMediaTypes("application/pdf")
                            .build());
                    observer.onCompleted();
                  }

                  @Override
                  public StreamObserver<DocumentRendererRenderRequest> render(
                      StreamObserver<DocumentRendererRenderResponse> responses) {
                    return new StreamObserver<>() {
                      @Override
                      public void onNext(DocumentRendererRenderRequest part) {
                        if (part.hasBlock()) {
                          drawn.add(part.getBlock());
                        }
                      }

                      @Override
                      public void onError(Throwable error) {
                        // The core hung up. Nothing to undo in a test renderer.
                      }

                      @Override
                      public void onCompleted() {
                        responses.onNext(
                            DocumentRendererRenderResponse.newBuilder()
                                .setHeader(
                                    RenderedHeader.newBuilder()
                                        .setMediaType("application/pdf")
                                        .setSuggestedFilename("from-a-plugin.pdf")
                                        .build())
                                .build());
                        responses.onNext(
                            DocumentRendererRenderResponse.newBuilder()
                                .setContent(
                                    ByteString.copyFrom(
                                        "%PDF- rendered for a plugin", StandardCharsets.UTF_8))
                                .build());
                        responses.onCompleted();
                      }
                    };
                  }
                })
            .build()
            .start();
    RENDERER.set(server);
    return server.getPort();
  }

  /** The renderer's identity, issued once for the class. */
  private static TestPki.Identity rendererIdentity() throws Exception {
    if (RENDERER_IDENTITY.get() == null) {
      RENDERER_IDENTITY.compareAndSet(null, PKI.issue("localhost"));
    }
    return RENDERER_IDENTITY.get();
  }

  /** The calling plugin's identity, which is not the renderer's. */
  private static TestPki.Identity callerIdentity() throws Exception {
    if (CALLER_IDENTITY.get() == null) {
      CALLER_IDENTITY.compareAndSet(null, PKI.issue("a-calling-plugin"));
    }
    return CALLER_IDENTITY.get();
  }

  /**
   * A port nothing is listening on, as far as the operating system knows.
   *
   * @return the port
   */
  private static int freePort() {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (IOException noSocket) {
      throw new IllegalStateException("No free port for the host channel", noSocket);
    }
  }

  private UUID aTenant(String name) {
    UUID userId = UUID.randomUUID();
    transactions.executeWithoutResult(
        status ->
            users.save(
                AppUser.create(
                    userId,
                    "host-channel-" + name + "@example.org",
                    "Owner",
                    "en",
                    passwordEncoder.encode(PASSWORD),
                    Instant.now())));
    enrolSecondFactor(userId);
    return provisioning.provision("Host channel " + name, userId);
  }

  /**
   * What came back.
   *
   * @param content the bytes
   * @param mediaType what they are
   */
  private record Rendered(byte[] content, String mediaType) {}
}
