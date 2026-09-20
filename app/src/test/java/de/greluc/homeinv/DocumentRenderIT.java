/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.inventory.api.ItemKind;
import de.greluc.homeinv.inventory.api.ItemService;
import de.greluc.homeinv.inventory.api.Valuation;
import de.greluc.homeinv.inventory.api.InsuranceDocuments;
import de.greluc.homeinv.platform.Money;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.plugin.api.port.DocumentRenderer;
import de.greluc.homeinv.plugin.v1.Block;
import de.greluc.homeinv.plugin.v1.DocumentRendererFormatsRequest;
import de.greluc.homeinv.plugin.v1.DocumentRendererFormatsResponse;
import de.greluc.homeinv.plugin.v1.DocumentRendererGrpc;
import de.greluc.homeinv.plugin.v1.DocumentRendererRenderRequest;
import de.greluc.homeinv.plugin.v1.DocumentRendererRenderResponse;
import de.greluc.homeinv.plugin.v1.RenderedHeader;
import de.greluc.homeinv.plugins.application.DefaultPluginRegistry;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import io.grpc.Grpc;
import io.grpc.Server;
import io.grpc.ServerCredentials;
import io.grpc.TlsServerCredentials;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The insurance report as a document, rendered by a plugin (REQ-LIFE-016, ADR-0070).
 *
 * <p>Against a <b>real</b> renderer over a real TLS socket, the way {@code NotificationDeliveryIT}
 * proves its port: what is being asserted is the contract between the core and a plugin, and a
 * mocked stub would assert the mock.
 *
 * <p>What matters here is the division ADR-0070 draws. The core sends a document — headings, facts,
 * a picture — and receives bytes. It never says what the document should look like, and the plugin
 * never decides which figures belong in it. The assertions are on the <b>blocks the renderer
 * received</b>, because that is the part of the contract the core is responsible for.
 */
@DisplayName("An insurance report as a document")
class DocumentRenderIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple-42";
  private static final Currency EUR = Currency.getInstance("EUR");
  private static final String PLUGIN = "de.greluc.homeinv.plugin.test.renderer.";

  private static final AtomicReference<Server> SERVER = new AtomicReference<>();
  private static final AtomicReference<TestPki.Identity> IDENTITY = new AtomicReference<>();

  @Autowired private InsuranceDocuments documents;
  @Autowired private ItemService items;
  @Autowired private de.greluc.homeinv.locations.api.LocationService locations;
  @Autowired private DefaultPluginRegistry registrations;
  @Autowired private AppUserRepository users;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private org.springframework.jdbc.core.simple.JdbcClient jdbc;

  @AfterAll
  static void stopTheServer() {
    Server running = SERVER.getAndSet(null);
    if (running != null) {
      running.shutdownNow();
    }
  }

  @Test
  @DisplayName("sends what the report says and receives what the renderer made of it")
  void theCoreDescribesAndThePluginRenders() throws Exception {
    List<Block> received = new ArrayList<>();
    AtomicReference<String> title = new AtomicReference<>();
    int port = startRenderer(received, title);

    Tenant tenant = newTenant("render-ok@example.org");
    consent(tenant.tenantId(), port, "ok");

    UUID study = aPlace(tenant, "A study");
    anItem(tenant, "A camera", study, new BigDecimal("1200.00"));

    DocumentRenderer.Rendered rendered =
        TenantContext.callAs(
            tenant.tenantId(),
            () -> documents.render(null, 50, "application/pdf", "en"));

    assertThat(rendered.mediaType()).isEqualTo("application/pdf");
    assertThat(new String(rendered.content(), StandardCharsets.UTF_8)).startsWith("%PDF-");

    // The header travelled, and the title is the core's.
    assertThat(title.get()).startsWith("Insurance report");

    // The blocks are the core's decisions: a heading, the totals as facts, the
    // room, the item, and the figure with the day it was true. Nothing here is
    // about fonts or spacing, which is the division ADR-0070 draws.
    assertThat(received).isNotEmpty();
    assertThat(received.stream().filter(Block::hasHeading).map(b -> b.getHeading().getText()))
        .contains("Insurance report", "Total", "A camera");
    assertThat(
            received.stream()
                .filter(Block::hasFacts)
                .flatMap(b -> b.getFacts().getEntriesList().stream())
                .map(fact -> fact.getLabel() + "=" + fact.getValue()))
        .contains("Figure=Replacement value", "Currency conversion=none")
        .anyMatch(fact -> fact.startsWith("Replacement value=1200.00 EUR"));

    // A room starts on its own page. A statement about the report rather than
    // about typography, which is why the core says it and not the renderer.
    assertThat(received.stream().filter(Block::hasPageBreak).count()).isEqualTo(1);
  }

  @Test
  @DisplayName("refuses with a sentence when nothing is installed that can render one")
  void noRendererIsAnAnswerRatherThanAnEmptyFile() {
    Tenant tenant = newTenant("render-none@example.org");
    UUID study = aPlace(tenant, "A study");
    anItem(tenant, "A camera", study, new BigDecimal("1200.00"));

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    tenant.tenantId(),
                    () -> documents.render(null, 50, "application/pdf", "en")))
        .isInstanceOf(InsuranceDocuments.NoRendererException.class)
        // The figures are there; what is missing is a plugin. A caller told that
        // goes to the other two endpoints rather than reporting a fault.
        .hasMessageContaining("available as data and as a table");
  }

  // -------------------------------------------------------------------------

  /**
   * Starts a renderer that records what it was asked to draw.
   *
   * @param received where the blocks are collected
   * @param title where the document's title is recorded
   * @return the port it listens on
   * @throws Exception when the socket cannot be opened
   */
  private static int startRenderer(List<Block> received, AtomicReference<String> title)
      throws Exception {
    stopTheServer();
    ServerCredentials credentials =
        TlsServerCredentials.newBuilder()
            .keyManager(
                new ByteArrayInputStream(Files.readAllBytes(identity().bundle())),
                new ByteArrayInputStream(Files.readAllBytes(identity().bundle())))
            .trustManager(new ByteArrayInputStream(PKI.caPem().getBytes(StandardCharsets.UTF_8)))
            .clientAuth(TlsServerCredentials.ClientAuth.REQUIRE)
            .build();
    Server server =
        Grpc.newServerBuilderForPort(0, credentials)
            .addService(renderer(received, title))
            .build()
            .start();
    SERVER.set(server);
    return server.getPort();
  }

  /**
   * A renderer that answers with something PDF-shaped.
   *
   * <p>It does not lay anything out: what this test is about is the contract, and a renderer that
   * actually produced a PDF would be testing a PDF library.
   *
   * @param received where the blocks are collected
   * @param title where the title is recorded
   * @return the service
   */
  private static DocumentRendererGrpc.DocumentRendererImplBase renderer(
      List<Block> received, AtomicReference<String> title) {
    return new DocumentRendererGrpc.DocumentRendererImplBase() {

      @Override
      public void outputFormats(
          DocumentRendererFormatsRequest request,
          StreamObserver<DocumentRendererFormatsResponse> observer) {
        observer.onNext(
            DocumentRendererFormatsResponse.newBuilder().addMediaTypes("application/pdf").build());
        observer.onCompleted();
      }

      @Override
      public StreamObserver<DocumentRendererRenderRequest> render(
          StreamObserver<DocumentRendererRenderResponse> responses) {
        return new StreamObserver<>() {
          @Override
          public void onNext(DocumentRendererRenderRequest part) {
            if (part.hasHeader()) {
              title.set(part.getHeader().getTitle());
            } else {
              received.add(part.getBlock());
            }
          }

          @Override
          public void onError(Throwable error) {
            // The core hung up. Nothing to clean up in a test renderer.
          }

          @Override
          public void onCompleted() {
            responses.onNext(
                DocumentRendererRenderResponse.newBuilder()
                    .setHeader(
                        RenderedHeader.newBuilder()
                            .setMediaType("application/pdf")
                            .setSuggestedFilename("insurance.pdf")
                            .build())
                    .build());
            responses.onNext(
                DocumentRendererRenderResponse.newBuilder()
                    .setContent(
                        ByteString.copyFrom("%PDF-1.7 rendered", StandardCharsets.UTF_8))
                    .build());
            responses.onCompleted();
          }
        };
      }
    };
  }

  /** The renderer's identity, issued once for the class. */
  private static TestPki.Identity identity() throws Exception {
    if (IDENTITY.get() == null) {
      IDENTITY.compareAndSet(null, PKI.issue("localhost"));
    }
    return IDENTITY.get();
  }

  private void consent(UUID tenantId, int port, String name) throws Exception {
    String pluginId = PLUGIN + name;
    registrations.register(
        manifest(pluginId).getBytes(StandardCharsets.UTF_8),
        "localhost:" + port,
        identity().fingerprint(),
        true);
    TenantContext.runAs(
        tenantId, () -> registrations.grant(pluginId, "network:outbound", UUID.randomUUID()));
  }

  private static String manifest(String pluginId) {
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

  private UUID anItem(Tenant tenant, String name, UUID where, BigDecimal replacement) {
    return TenantContext.callAs(
        tenant.tenantId(),
        () ->
            transactions.execute(
                status ->
                    items
                        .create(
                            new ItemService.CreateItemCommand(
                                null,
                                builtinType(tenant.tenantId()),
                                name,
                                null,
                                ItemKind.PHYSICAL,
                                where,
                                BigDecimal.ONE,
                                null,
                                "{}",
                                null,
                                null,
                                new Valuation(
                                    null,
                                    null,
                                    null,
                                    null,
                                    false,
                                    new Money(replacement, EUR),
                                    LocalDate.of(2026, 1, 1),
                                    Valuation.Provenance.MANUAL,
                                    null,
                                    null)),
                            Optional.empty(),
                            tenant.userId())
                        .item()
                        .id()));
  }

  private UUID aPlace(Tenant tenant, String name) {
    return TenantContext.callAs(
        tenant.tenantId(),
        () ->
            transactions.execute(
                status ->
                    locations
                        .create(
                            new de.greluc.homeinv.locations.api.LocationService
                                .CreateLocationCommand(
                                null,
                                anyCategory(tenant.tenantId()),
                                null,
                                name + " " + UUID.randomUUID(),
                                null),
                            Optional.empty(),
                            tenant.userId())
                        .id()));
  }

  private UUID anyCategory(UUID tenantId) {
    return jdbc
        .sql("select id from catalog.location_category where tenant_id = ? and key = 'box'")
        .param(tenantId)
        .query(UUID.class)
        .single();
  }

  private UUID builtinType(UUID tenantId) {
    return jdbc
        .sql("select id from catalog.item_type where tenant_id = ? and key = 'general'")
        .param(tenantId)
        .query(UUID.class)
        .single();
  }

  private Tenant newTenant(String email) {
    UUID userId = UUID.randomUUID();
    transactions.executeWithoutResult(
        status ->
            users.save(
                AppUser.create(
                    userId, email, "Reporter", "en", passwordEncoder.encode(PASSWORD),
                    Instant.now())));
    enrolSecondFactor(userId);
    return new Tenant(userId, provisioning.provision("Tenant of " + email, userId));
  }

  private record Tenant(UUID userId, UUID tenantId) {}
}
