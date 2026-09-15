/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.homeinv.idempotency.infrastructure.IdempotencyErasure;
import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.platform.TenantErasure;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Proves a retry creates nothing a second time (REQ-API-005).
 *
 * <p>The acceptance criterion has two halves and the second is the one that decides the design: "a
 * repeat returns the original result" and "a repeat <b>after the cache has been cleared</b> still
 * returns the original result rather than creating a second record". There is no cache to clear
 * here, and that is the point — the record is a row in PostgreSQL, written in the same transaction
 * as the entity it protects (ADR-0009, open point O13). The test therefore checks the row as well
 * as the answer.
 */
@DisplayName("A repeated request")
class IdempotencyIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple-42";

  @Autowired private TenantProvisioningService provisioning;
  @Autowired private AppUserRepository users;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private JdbcClient jdbc;
  @Autowired private ObjectMapper json;
  @Autowired private IdempotencyErasure erasure;

  @Test
  @DisplayName("creates one item and answers the first result again")
  void aRepeatCreatesNothing() throws Exception {
    Session session = tenantSession("items");
    String key = UUID.randomUUID().toString();
    String body = "{\"name\":\"Kettle\",\"kind\":\"DIGITAL\"}";

    String first =
        mockMvc
            .perform(
                post("/api/v1/items")
                    .session(session.http())
                    .with(csrf())
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    UUID id = UUID.fromString(json.readTree(first).get("id").asString());

    // The same key and the same body: the same answer, and no second item.
    String again =
        mockMvc
            .perform(
                post("/api/v1/items")
                    .session(session.http())
                    .with(csrf())
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    assertThat(json.readTree(again).get("id").asString()).isEqualTo(id.toString());
    assertThat(itemsNamed(session.tenantId(), "Kettle")).isEqualTo(1);

    // Reformatted -- more whitespace, the fields the other way round -- is the
    // same request. A client that retries through a different HTTP library must
    // not be told it sent something else.
    mockMvc
        .perform(
            post("/api/v1/items")
                .session(session.http())
                .with(csrf())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"kind\" : \"DIGITAL\" ,\n  \"name\" : \"Kettle\" }"))
        .andExpect(status().isCreated());
    assertThat(itemsNamed(session.tenantId(), "Kettle")).isEqualTo(1);

    // And the record is a row, not a cache entry: the requirement's second half
    // is about surviving a cache being cleared, and there is nothing to clear.
    assertThat(recordsFor(session.tenantId())).isEqualTo(1);
  }

  @Test
  @DisplayName("with the same key and a different body is refused rather than answered")
  void oneKeyIsOneRequest() throws Exception {
    Session session = tenantSession("conflict");
    String key = UUID.randomUUID().toString();

    mockMvc
        .perform(
            post("/api/v1/items")
                .session(session.http())
                .with(csrf())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Lamp\",\"kind\":\"DIGITAL\"}"))
        .andExpect(status().isCreated());

    // A different body under a spent key is a client bug. Answering it with the
    // lamp would make a request that created nothing look like one that worked.
    mockMvc
        .perform(
            post("/api/v1/items")
                .session(session.http())
                .with(csrf())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Radio\",\"kind\":\"DIGITAL\"}"))
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.type")
                .value("https://home-inv.example/problems/idempotency-key-conflict"));
    assertThat(itemsNamed(session.tenantId(), "Radio")).isZero();

    // The same key at a different endpoint is the same bug: a key is spent once,
    // not once per path.
    String categories =
        mockMvc
            .perform(get("/api/v1/locations/categories?limit=200").session(session.http()))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    String categoryId = json.readTree(categories).get("data").get(0).get("id").asString();

    mockMvc
        .perform(
            post("/api/v1/locations")
                .session(session.http())
                .with(csrf())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Shed\",\"categoryId\":\"" + categoryId + "\"}"))
        .andExpect(status().isConflict());
  }

  @Test
  @DisplayName("without a key behaves as it always did")
  void theKeyIsOptional() throws Exception {
    Session session = tenantSession("optional");
    String body = "{\"name\":\"Spare\",\"kind\":\"DIGITAL\"}";

    for (int attempt = 0; attempt < 2; attempt++) {
      mockMvc
          .perform(
              post("/api/v1/items")
                  .session(session.http())
                  .with(csrf())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isCreated());
    }
    // Two requests with no key are two requests. The header is what makes a
    // repeat a repeat, which is what keeps it adoptable one client at a time.
    assertThat(itemsNamed(session.tenantId(), "Spare")).isEqualTo(2);
    assertThat(recordsFor(session.tenantId())).isZero();
  }

  @Test
  @DisplayName("is refused when the key itself is not one this API can store")
  void theKeyIsBounded() throws Exception {
    Session session = tenantSession("bounded");

    mockMvc
        .perform(
            post("/api/v1/items")
                .session(session.http())
                .with(csrf())
                .header("Idempotency-Key", "x".repeat(256))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Long\",\"kind\":\"DIGITAL\"}"))
        .andExpect(status().isUnprocessableEntity());

    mockMvc
        .perform(
            post("/api/v1/items")
                .session(session.http())
                .with(csrf())
                .header("Idempotency-Key", "has a space")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Spaced\",\"kind\":\"DIGITAL\"}"))
        .andExpect(status().isUnprocessableEntity());

    assertThat(recordsFor(session.tenantId())).isZero();
  }

  @Test
  @DisplayName("stops being remembered after 24 hours, and the record goes with it")
  void recordsExpire() throws Exception {
    Session session = tenantSession("expiry");
    String key = UUID.randomUUID().toString();
    String body = "{\"name\":\"Ephemeral\",\"kind\":\"DIGITAL\"}";

    mockMvc
        .perform(
            post("/api/v1/items")
                .session(session.http())
                .with(csrf())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isCreated());
    assertThat(recordsFor(session.tenantId())).isEqualTo(1);

    // Backdated rather than waited for, and written as a delete and an insert
    // because the application has no UPDATE on this table and should not: a
    // record is made once and expires, and nothing edits one.
    backdate(session.tenantId(), key);

    // Past its day, the record is treated as absent even though it is still a
    // row. That is the point of expiring on READ: "valid for 24 h" is true at the
    // moment it matters rather than at the moment a job last succeeded.
    mockMvc
        .perform(
            post("/api/v1/items")
                .session(session.http())
                .with(csrf())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isCreated());
    assertThat(itemsNamed(session.tenantId(), "Ephemeral")).isEqualTo(2);

    // And the stale row is gone, swept by the very write that ignored it: one
    // record for the key that was just spent, none for the day before.
    assertThat(recordsFor(session.tenantId())).isEqualTo(1);
  }

  @Test
  @DisplayName("is forgotten when the tenant is, because the answers were the tenant's")
  void erasureTakesTheRecords() throws Exception {
    Session session = tenantSession("erasure");
    mockMvc
        .perform(
            post("/api/v1/items")
                .session(session.http())
                .with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Traceable\",\"kind\":\"DIGITAL\"}"))
        .andExpect(status().isCreated());
    assertThat(recordsFor(session.tenantId())).isEqualTo(1);

    // The block's own share of REQ-TEN-011. It is also the only thing that ever
    // clears a tenant that stopped writing: expiry runs on the path that spends a
    // key, and a tenant spending none has none to expire.
    TenantErasure.BlockReport report =
        TenantContext.callAs(
            session.tenantId(), () -> erasure.erase(session.tenantId()));
    assertThat(report.block()).isEqualTo("idempotency");
    assertThat(report.rowsRemoved()).isEqualTo(1);
    assertThat(recordsFor(session.tenantId())).isZero();
  }

  // -------------------------------------------------------------------------

  /**
   * Moves a record a day into the past.
   *
   * @param tenantId whose record
   * @param key which one
   */
  private void backdate(UUID tenantId, String key) {
    inTenant(
        tenantId,
        () -> {
          jdbc.sql("delete from idempotency.processed_request where key = ?").param(key).update();
          return jdbc
              .sql(
                  """
                  insert into idempotency.processed_request
                      (tenant_id, key, request_hash, operation, response, created_at)
                  values (?, ?, ?, ?, '{}'::jsonb, now() - interval '25 hours')
                  """)
              .params(tenantId, key, "0".repeat(64), "POST /api/v1/items")
              .update();
        });
  }

  private int itemsNamed(UUID tenantId, String name) {
    return inTenant(
        tenantId,
        () ->
            jdbc
                .sql("select count(*) from inventory.item where name = ? and deleted_at is null")
                .param(name)
                .query(Integer.class)
                .single());
  }

  private int recordsFor(UUID tenantId) {
    return inTenant(
        tenantId,
        () ->
            jdbc.sql("select count(*) from idempotency.processed_request")
                .query(Integer.class)
                .single());
  }

  private <T> T inTenant(UUID tenantId, java.util.function.Supplier<T> body) {
    return TenantContext.callAs(tenantId, () -> transactions.execute(status -> body.get()));
  }

  private Session tenantSession(String name) throws Exception {
    String email = "idempotency-" + name + "@example.org";
    UUID userId = UUID.randomUUID();
    transactions.executeWithoutResult(
        status ->
            users.save(
                AppUser.create(
                    userId,
                    email,
                    "Retrier",
                    "en",
                    passwordEncoder.encode(PASSWORD),
                    Instant.now())));
    enrolSecondFactor(userId);
    UUID tenantId = provisioning.provision("Idempotency " + name, userId);
    return new Session(signIn(email, PASSWORD), tenantId);
  }

  /**
   * A signed-in owner and the tenant they own.
   *
   * @param http the session
   * @param tenantId the tenant, for the queries that count rows
   */
  private record Session(MockHttpSession http, UUID tenantId) {}
}
