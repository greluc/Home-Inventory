/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.homeinv.identity.api.AccountAdministration;
import de.greluc.homeinv.identity.api.UserProvisioning;
import de.greluc.homeinv.tenancy.api.TenantProvisioning;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The one-shot {@code bootstrap} service needs only the secrets it is given (ADR-0053, ADR-0041).
 *
 * <h2>What broke, and why nothing caught it</h2>
 *
 * <p>On 2026-09-13 {@code BootstrapRunner} gained a dependency on {@code AccountAdministration}, so
 * that the first account could be made an instance operator (ADR-0057). That bean reached a paged
 * listing, the listing signed a cursor, and the cursor needed
 * {@code HOMEINV_URL_SIGNING_KEY_FILE} — which the {@code bootstrap} service is deliberately not
 * given, because it serves no request and signs nothing. A fresh deployment stopped at "Error
 * starting ApplicationContext", and the whole stack with it.
 *
 * <p>Every other test in this suite has a signing key, because the build generates one for them.
 * That is what made the gap invisible until the smoke suite ran a real stack twenty minutes later.
 *
 * <h2>How it reproduces the one-shot</h2>
 *
 * <p>Three properties, and each is what {@code application-bootstrap.yaml} sets: no web layer, lazy
 * initialisation — so that only what the runner actually reaches is created, which is the whole
 * point — and no signing key. A context of its own rather than the shared one, because the shared
 * one is a web context and its controllers need the key for their own good reasons.
 *
 * <p>It does not run {@link de.greluc.homeinv.bootstrap.BootstrapRunner} itself: that ends in
 * {@code System.exit}, which in a test would take the JVM with it. It drives the three ports the
 * runner calls, which is where the wiring is.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "spring.main.lazy-initialization=true",
      "spring.main.web-application-type=none",
      "homeinv.security.url-signing-key-file="
    })
@ActiveProfiles("test")
@Import(TestBlobStore.class)
@DisplayName("The bootstrap one-shot")
class BootstrapIsolationIT {

  @Autowired private UserProvisioning users;
  @Autowired private AccountAdministration accounts;
  @Autowired private TenantProvisioning tenants;

  /**
   * The same containers the rest of the suite uses, started by touching the class that owns them.
   *
   * @param registry where the connection details go
   */
  @DynamicPropertySource
  static void containerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", AbstractIntegrationTest.POSTGRES::getJdbcUrl);
    registry.add("spring.flyway.url", AbstractIntegrationTest.POSTGRES::getJdbcUrl);
    registry.add("spring.data.redis.host", AbstractIntegrationTest.VALKEY::getHost);
    registry.add("spring.data.redis.port", () -> AbstractIntegrationTest.VALKEY.getMappedPort(6379));
    registry.add("spring.rabbitmq.host", AbstractIntegrationTest.RABBITMQ::getHost);
    registry.add("spring.rabbitmq.port", () -> AbstractIntegrationTest.RABBITMQ.getMappedPort(5672));
    registry.add("HOMEINV_BLOBSTORE_ENDPOINT", () -> "blobstore.invalid:8100");
    registry.add(
        "HOMEINV_BLOBSTORE_FINGERPRINT", AbstractIntegrationTest.BLOBSTORE_IDENTITY::fingerprint);
    registry.add(
        "HOMEINV_MTLS_CORE_FILE", () -> AbstractIntegrationTest.CORE_IDENTITY.bundle().toString());
  }

  @Test
  @DisplayName("creates the first owner, entitles them and provisions a tenant, with no signing key")
  void withoutASigningKey() {
    String email = "bootstrap-isolation@example.org";
    UUID userId =
        users
            .createIfAbsent(email, "Owner", "en", "correct-horse-battery-staple-42")
            .orElseThrow();

    // The question the runner asks on a second run, and the reason it must not be
    // answered by a paged listing: paging signs a cursor.
    assertThat(accounts.hasInstanceOperator()).isFalse();

    accounts.replaceEntitlements(userId, true, true, null, userId);
    assertThat(accounts.hasInstanceOperator()).isTrue();
    assertThat(accounts.byEmail(email))
        .get()
        .extracting(AccountAdministration.AccountView::instanceOperator)
        .isEqualTo(true);

    assertThat(tenants.provision("Home", userId)).isNotNull();
  }
}
