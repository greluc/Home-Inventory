/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.homeinv.eventstream.api.LiveStreams;
import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * An open view hears about a change somebody else made (REQ-API-011).
 *
 * <p>The whole round trip, not a unit of it: a browser holds a stream, another session creates an
 * item, the change is published to Valkey when the transaction commits, every replica's subscriber
 * hears it, and the stream that belongs to that tenant receives a nudge. With one application
 * context the Valkey hop could have been a method call — and it is the hop that makes the feature
 * work when {@code api} runs more than one replica, which is the case it exists for.
 *
 * <p>What is asserted about the payload is as important as what arrives: a <b>kind</b> and a
 * moment, and no id. A view re-reads what it shows through the ordinary API, which applies the
 * ordinary permissions.
 */
@DisplayName("The live change stream")
class EventStreamIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple-42";

  @Autowired private AppUserRepository users;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private LiveStreams streams;

  @Test
  @DisplayName("says it is open, and then says what kind of thing changed")
  void aChangeReachesAnOpenView() throws Exception {
    Tenant tenant = aTenant("watcher");
    MockHttpSession watching = signIn(tenant.email(), PASSWORD);

    MvcResult stream =
        mockMvc
            .perform(get("/api/v1/events").session(watching))
            .andExpect(request().asyncStarted())
            .andReturn();

    // Immediately, so a client knows the stream is open rather than merely
    // accepted -- and so a proxy that buffers is caught here rather than in
    // somebody's house.
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> assertThat(stream.getResponse().getContentAsString()).contains("\"kind\":\"open\""));

    assertThat(streams.openStreams(tenant.tenantId())).isEqualTo(1);

    // Somebody else in the same tenant adds something.
    MockHttpSession other = signIn(tenant.email(), PASSWORD);
    createAnItem(other, tenant);

    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () -> {
              String received = stream.getResponse().getContentAsString();
              assertThat(received).contains("event:item");
              assertThat(received).contains("\"kind\":\"item\"");
              // NOT the id, and not the name. The view re-reads what it shows.
              assertThat(received).doesNotContain(tenant.itemName());
            });
  }

  @Test
  @DisplayName("ends at once for a session that acts for no tenant")
  void nothingToStream() throws Exception {
    // An instance operator, or somebody between tenants. A state rather than a
    // failure -- so the stream ends rather than waiting half an hour for nothing.
    String email = "nowhere-" + UUID.randomUUID() + "@example.org";
    UUID userId = anAccount(email);

    MvcResult result =
        mockMvc.perform(get("/api/v1/events").session(signIn(email, PASSWORD))).andReturn();

    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    assertThat(streams.openStreams(userId)).isZero();
  }

  @Test
  @DisplayName("is for the caller's own tenant and no other")
  void oneTenantsChangeIsNotAnothersBusiness() throws Exception {
    Tenant watcher = aTenant("first");
    Tenant stranger = aTenant("second");

    MvcResult stream =
        mockMvc
            .perform(get("/api/v1/events").session(signIn(watcher.email(), PASSWORD)))
            .andExpect(request().asyncStarted())
            .andReturn();
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> assertThat(stream.getResponse().getContentAsString()).contains("\"kind\":\"open\""));

    createAnItem(signIn(stranger.email(), PASSWORD), stranger);

    // Nothing arrives, and "nothing" is asserted by waiting long enough for
    // something to have arrived if it were going to.
    Thread.sleep(2_000);
    assertThat(stream.getResponse().getContentAsString()).doesNotContain("\"kind\":\"item\"");
  }

  // -------------------------------------------------------------------------

  private void createAnItem(MockHttpSession session, Tenant tenant) throws Exception {
    mockMvc
        .perform(
            post("/api/v1/items")
                .session(session)
                .with(SecurityMockMvcRequestPostProcessors.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"" + tenant.itemName() + "\",\"kind\":\"DIGITAL\"}"))
        .andExpect(status().isCreated());
  }

  private UUID anAccount(String email) {
    UUID userId = UUID.randomUUID();
    transactions.executeWithoutResult(
        status ->
            users.save(
                AppUser.create(
                    userId, email, "Watcher", "en", passwordEncoder.encode(PASSWORD),
                    Instant.now())));
    enrolSecondFactor(userId);
    return userId;
  }

  private Tenant aTenant(String name) {
    String email = name + "-" + UUID.randomUUID() + "@example.org";
    UUID userId = anAccount(email);
    UUID tenantId = provisioning.provision("Tenant of " + email, userId);
    return new Tenant(email, tenantId, "A thing called " + UUID.randomUUID());
  }

  /**
   * One tenant and the account that owns it.
   *
   * @param email the account's address, which is also its password's owner
   * @param tenantId the tenant
   * @param itemName a name unique to this test, so that its absence from the stream means
   *     something
   */
  private record Tenant(String email, UUID tenantId, String itemName) {}
}
