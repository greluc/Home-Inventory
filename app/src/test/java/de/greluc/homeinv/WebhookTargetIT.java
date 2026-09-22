/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Webhook targets, and the delivery a change queues for them (REQ-API-010).
 *
 * <p>The half of the feature that is the core's. Nothing here posts anything to a receiver —
 * {@code plugin-webhook} does that, from its own network segment, and the core has no outbound
 * route at all (ADR-0026). What is asserted is everything up to the moment the delivery is handed
 * over: which targets a change reaches, what the document says, and what a person may configure.
 *
 * <p>The delivery is read out of the database rather than through an endpoint wherever the
 * <b>document</b> is the subject, because the document is what a stranger's server receives and no
 * endpoint returns it.
 */
@DisplayName("Webhook targets")
class WebhookTargetIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple-42";

  /** Long enough to be a secret, and obviously not one: it is in a public repository. */
  private static final String SECRET = "test-only-not-a-secret-0123456789abcdef";

  private static final String RECEIVER = "https://hooks.example.org/inventory";

  @Autowired private AppUserRepository users;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private JdbcClient jdbc;

  // -------------------------------------------------------------------------
  // Configuring one
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("are created without their secret ever coming back out")
  void theSecretGoesInAndNeverComesOut() throws Exception {
    Tenant tenant = aTenant();
    MockHttpSession session = signIn(tenant.email(), PASSWORD);

    String created =
        mockMvc
            .perform(
                post("/api/v1/webhooks")
                    .session(session)
                    .with(SecurityMockMvcRequestPostProcessors.csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(targetBody(RECEIVER, "item.created")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.url").value(RECEIVER))
            .andExpect(jsonPath("$.enabled").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(created).doesNotContain(SECRET);
    assertThat(created).doesNotContain("signingSecret");

    String listed =
        mockMvc
            .perform(get("/api/v1/webhooks").session(session))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(listed).doesNotContain(SECRET);

    // And it is sealed at rest, not merely absent from the responses.
    UUID targetId = UUID.fromString(read(created).get("id").asText());
    String stored =
        TenantContext.callAs(
            tenant.tenantId(),
            () ->
                transactions.execute(
                    status ->
                        jdbc
                            .sql(
                                "select signing_secret from notification.webhook_target"
                                    + " where tenant_id = ? and id = ?")
                            .param(tenant.tenantId())
                            .param(targetId)
                            .query(String.class)
                            .single()));
    assertThat(stored).isNotEqualTo(SECRET).isNotBlank();
  }

  @Test
  @DisplayName("refuse a plain http receiver, a short secret and an event nobody raises")
  void whatIsRefusedWhenItIsStillFixable() throws Exception {
    MockHttpSession session = signIn(aTenant().email(), PASSWORD);

    // Each of these is `validation-failed`, which is 422 and not 400: the
    // request parsed, and what is wrong is what it says (08 §8.2).

    // REQ-SEC-034. The payload and its signature would cross the internet in
    // clear text, and the plugin refuses it as well -- this is the check that
    // reaches the person who can still correct it.
    expectRefusal(session, targetBody("http://hooks.example.org/x", "item.created"));

    // A secret somebody typed rather than generated.
    expectRefusal(
        session,
        "{\"url\":\"https://hooks.example.org/short\",\"eventTypes\":[\"item.created\"],"
            + "\"signingSecret\":\"hunter2\"}");

    // A subscription that would never fire, and the message says which name is
    // wrong rather than that something is.
    mockMvc
        .perform(
            post("/api/v1/webhooks")
                .session(session)
                .with(SecurityMockMvcRequestPostProcessors.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(targetBody("https://hooks.example.org/unknown", "item.exploded")))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("item.exploded")));

    // A subscription to nothing at all. Refused by the bean constraint on the
    // request, which is a 400: the body is the wrong shape rather than wrong.
    mockMvc
        .perform(
            post("/api/v1/webhooks")
                .session(session)
                .with(SecurityMockMvcRequestPostProcessors.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"url\":\"https://hooks.example.org/none\",\"eventTypes\":[],"
                        + "\"signingSecret\":\""
                        + SECRET
                        + "\"}"))
        .andExpect(status().is4xxClientError());
  }

  @Test
  @DisplayName("refuse a second target at an address this tenant already uses")
  void oneTargetPerAddress() throws Exception {
    MockHttpSession session = signIn(aTenant().email(), PASSWORD);
    create(session, RECEIVER, "item.created");

    mockMvc
        .perform(
            post("/api/v1/webhooks")
                .session(session)
                .with(SecurityMockMvcRequestPostProcessors.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(targetBody(RECEIVER, "item.moved")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value("https://home-inv.example/problems/resource-exists"));
  }

  @Test
  @DisplayName("belong to their tenant and to no other")
  void anotherTenantsTargetIsNotFound() throws Exception {
    Tenant mine = aTenant();
    Tenant theirs = aTenant();
    UUID targetId = create(signIn(mine.email(), PASSWORD), RECEIVER, "item.created");

    MockHttpSession stranger = signIn(theirs.email(), PASSWORD);
    mockMvc
        .perform(get("/api/v1/webhooks/" + targetId).session(stranger))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(get("/api/v1/webhooks/" + targetId + "/deliveries").session(stranger))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(
            delete("/api/v1/webhooks/" + targetId)
                .session(stranger)
                .with(SecurityMockMvcRequestPostProcessors.csrf()))
        .andExpect(status().isNotFound());

    // And it is still there for the tenant it belongs to.
    mockMvc
        .perform(get("/api/v1/webhooks/" + targetId).session(signIn(mine.email(), PASSWORD)))
        .andExpect(status().isOk());
  }

  // -------------------------------------------------------------------------
  // What a change queues
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("receive the type, the moment and the id of what changed — and nothing else")
  void aChangeQueuesADeliveryThatNamesNoNames() throws Exception {
    Tenant tenant = aTenant();
    MockHttpSession session = signIn(tenant.email(), PASSWORD);
    UUID targetId = create(session, RECEIVER, "item.created");

    String itemName = "A thing called " + UUID.randomUUID();
    UUID itemId = createAnItem(session, itemName);

    List<Delivery> queued = deliveriesOf(tenant.tenantId(), targetId);
    assertThat(queued).hasSize(1);

    Delivery delivery = queued.getFirst();
    assertThat(delivery.channelKey()).isEqualTo("webhook");
    assertThat(delivery.address()).isEqualTo(RECEIVER);
    assertThat(delivery.subject()).isEqualTo("item.created");
    assertThat(delivery.state()).isEqualTo("QUEUED");

    assertThat(delivery.body()).contains("\"type\":\"item.created\"");
    assertThat(delivery.body()).contains("\"id\":\"" + itemId + "\"");
    assertThat(delivery.body()).contains("\"at\":\"");
    // ADR-0078: the id, so a receiver can fetch it -- and not the data, which
    // would be a copy of the inventory on somebody else's server.
    assertThat(delivery.body()).doesNotContain(itemName);
  }

  @Test
  @DisplayName("receive only the event types they asked for")
  void anUnsubscribedTypeReachesNobody() throws Exception {
    Tenant tenant = aTenant();
    MockHttpSession session = signIn(tenant.email(), PASSWORD);
    UUID targetId = create(session, "https://hooks.example.org/moves", "item.moved");

    createAnItem(session, "Something new");

    assertThat(deliveriesOf(tenant.tenantId(), targetId))
        .as("a target subscribed to item.moved is not told about item.created")
        .isEmpty();
  }

  @Test
  @DisplayName("receive nothing while they are disabled")
  void aPausedTargetCollectsNothing() throws Exception {
    Tenant tenant = aTenant();
    MockHttpSession session = signIn(tenant.email(), PASSWORD);
    UUID targetId =
        createWith(
            session,
            "{\"url\":\"https://hooks.example.org/paused\",\"eventTypes\":[\"item.created\"],"
                + "\"signingSecret\":\""
                + SECRET
                + "\",\"enabled\":false}");

    createAnItem(session, "Something new");

    assertThat(deliveriesOf(tenant.tenantId(), targetId))
        .as("a receiver being repaired must not collect a dead-letter pile while it is off")
        .isEmpty();
  }

  @Test
  @DisplayName("hear nothing about another tenant's changes")
  void oneTenantsChangeIsNotAnothersBusiness() throws Exception {
    Tenant watcher = aTenant();
    Tenant stranger = aTenant();
    UUID targetId = create(signIn(watcher.email(), PASSWORD), RECEIVER, "item.created");

    createAnItem(signIn(stranger.email(), PASSWORD), "Not yours");

    assertThat(deliveriesOf(watcher.tenantId(), targetId)).isEmpty();
  }

  @Test
  @DisplayName("show what was delivered, and lose it when the target is removed")
  void theDeliveryLogGoesWithItsTarget() throws Exception {
    Tenant tenant = aTenant();
    MockHttpSession session = signIn(tenant.email(), PASSWORD);
    UUID targetId = create(session, RECEIVER, "item.created");
    createAnItem(session, "Something new");

    mockMvc
        .perform(get("/api/v1/webhooks/" + targetId + "/deliveries").session(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].channelKey").value("webhook"))
        .andExpect(jsonPath("$[0].state").value("QUEUED"));

    mockMvc
        .perform(
            delete("/api/v1/webhooks/" + targetId)
                .session(session)
                .with(SecurityMockMvcRequestPostProcessors.csrf()))
        .andExpect(status().isNoContent());

    // The cascade of V73: a delivery log about a receiver nobody can name any
    // more answers no question.
    assertThat(deliveriesOf(tenant.tenantId(), targetId)).isEmpty();
    mockMvc
        .perform(get("/api/v1/webhooks/" + targetId).session(session))
        .andExpect(status().isNotFound());
  }

  // -------------------------------------------------------------------------

  private void expectRefusal(MockHttpSession session, String body) throws Exception {
    mockMvc
        .perform(
            post("/api/v1/webhooks")
                .session(session)
                .with(SecurityMockMvcRequestPostProcessors.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(
            jsonPath("$.type").value("https://home-inv.example/problems/validation-failed"));
  }

  private UUID create(MockHttpSession session, String url, String eventType) throws Exception {
    return createWith(session, targetBody(url, eventType));
  }

  private UUID createWith(MockHttpSession session, String body) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/webhooks")
                    .session(session)
                    .with(SecurityMockMvcRequestPostProcessors.csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn();
    return UUID.fromString(read(result.getResponse().getContentAsString()).get("id").asText());
  }

  private static String targetBody(String url, String eventType) {
    return "{\"url\":\""
        + url
        + "\",\"eventTypes\":[\""
        + eventType
        + "\"],\"signingSecret\":\""
        + SECRET
        + "\"}";
  }

  private UUID createAnItem(MockHttpSession session, String name) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/items")
                    .session(session)
                    .with(SecurityMockMvcRequestPostProcessors.csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"" + name + "\",\"kind\":\"DIGITAL\"}"))
            .andExpect(status().isCreated())
            .andReturn();
    return UUID.fromString(read(result.getResponse().getContentAsString()).get("id").asText());
  }

  private List<Delivery> deliveriesOf(UUID tenantId, UUID targetId) {
    return TenantContext.callAs(
        tenantId,
        () ->
            transactions.execute(
                status ->
                    jdbc
                        .sql(
                            """
                            select channel_key, address, subject, body_text, state
                            from notification.notification
                            where tenant_id = ? and webhook_target_id = ?
                            order by created_at
                            """)
                        .param(tenantId)
                        .param(targetId)
                        .query(
                            (rs, row) ->
                                new Delivery(
                                    rs.getString("channel_key"),
                                    rs.getString("address"),
                                    rs.getString("subject"),
                                    rs.getString("body_text"),
                                    rs.getString("state")))
                        .list()));
  }

  private JsonNode read(String json) throws Exception {
    return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
  }

  private UUID anAccount(String email) {
    UUID userId = UUID.randomUUID();
    transactions.executeWithoutResult(
        status ->
            users.save(
                AppUser.create(
                    userId, email, "Owner", "en", passwordEncoder.encode(PASSWORD), Instant.now())));
    enrolSecondFactor(userId);
    return userId;
  }

  private Tenant aTenant() {
    String email = "webhooks-" + UUID.randomUUID() + "@example.org";
    UUID userId = anAccount(email);
    return new Tenant(email, provisioning.provision("Tenant of " + email, userId));
  }

  /**
   * One tenant and the owner that administers it.
   *
   * @param email the owner's address, which is also how the test signs in
   * @param tenantId the tenant
   */
  private record Tenant(String email, UUID tenantId) {}

  /**
   * One queued delivery, as the row holds it.
   *
   * @param channelKey which channel will carry it
   * @param address where it is addressed
   * @param subject the event type
   * @param body the document a receiver gets
   * @param state where it is in the delivery machinery of V51
   */
  private record Delivery(
      String channelKey, String address, String subject, String body, String state) {}
}
