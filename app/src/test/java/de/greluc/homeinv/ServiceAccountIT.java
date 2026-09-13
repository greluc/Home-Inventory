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

import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Machine access: a token with a role and an expiry (REQ-AUTH-010).
 *
 * <p>What is proved here is the whole loop through the API: an administrator issues one, the token
 * comes back once, a machine uses it on ordinary endpoints with the role it was given and not with
 * more, and a revocation stops it on the next request.
 */
@DisplayName("A service account")
class ServiceAccountIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple-42";

  @Autowired private AppUserRepository users;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private JdbcClient jdbc;
  @Autowired private ObjectMapper json;

  @Test
  @DisplayName("is issued once, reads with the role it was given, and stops when it is revoked")
  void issueUseAndRevoke() throws Exception {
    Fixture tenant = tenantWithOwner("sa-owner@example.org");
    MockHttpSession owner = signIn("sa-owner@example.org", PASSWORD);

    String created =
        mockMvc
            .perform(
                post("/api/v1/tenants/" + tenant.tenantId() + "/service-accounts")
                    .session(owner)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "name", "The stocktake scanner",
                                "role", "VIEWER",
                                "expiresAt", Instant.now().plus(30, ChronoUnit.DAYS).toString()))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.token").isNotEmpty())
            .andExpect(jsonPath("$.account.role").value("VIEWER"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    String token = json.readTree(created).get("token").asString();
    String accountId = json.readTree(created).get("account").get("id").asString();

    // The prefix is what makes a secret scanner recognise it in a configuration
    // file, and the listing never shows the token again.
    assertThat(token).startsWith("homeinv_sa_");
    mockMvc
        .perform(get("/api/v1/tenants/" + tenant.tenantId() + "/service-accounts").session(owner))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].name").value("The stocktake scanner"))
        .andExpect(jsonPath("$[0].token").doesNotExist());

    // The machine reads with its own token and no session at all.
    mockMvc
        .perform(get("/api/v1/search").param("q", "").param("language", "en").header(
            "Authorization", "Bearer " + token))
        .andExpect(status().isOk());

    // And is held to its role: a VIEWER creates nothing.
    mockMvc
        .perform(
            post("/api/v1/locations")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(
                    Map.of("name", "A shelf", "categoryId", tenant.categoryId().toString()))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.type").value("https://home-inv.example/problems/forbidden"));

    mockMvc
        .perform(
            delete("/api/v1/tenants/" + tenant.tenantId() + "/service-accounts/" + accountId)
                .session(owner)
                .with(csrf()))
        .andExpect(status().isNoContent());

    // The next request the machine makes has no credential.
    mockMvc
        .perform(get("/api/v1/search").param("q", "").param("language", "en").header(
            "Authorization", "Bearer " + token))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("stops working on its expiry date, and a token nobody issued never worked")
  void expiryAndNonsense() throws Exception {
    Fixture tenant = tenantWithOwner("sa-expiry@example.org");
    MockHttpSession owner = signIn("sa-expiry@example.org", PASSWORD);

    String created =
        mockMvc
            .perform(
                post("/api/v1/tenants/" + tenant.tenantId() + "/service-accounts")
                    .session(owner)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "name", "Short-lived",
                                "role", "VIEWER",
                                "expiresAt", Instant.now().plus(1, ChronoUnit.DAYS).toString()))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String token = json.readTree(created).get("token").asString();

    mockMvc
        .perform(get("/api/v1/search").param("q", "").param("language", "en").header(
            "Authorization", "Bearer " + token))
        .andExpect(status().isOk());

    // The date passes. Backdated rather than waited out, for the reason the
    // erasure test backdates a request: the period is the thing under test and a
    // shorter one from a setting would not be it.
    expire(tenant.tenantId());

    mockMvc
        .perform(get("/api/v1/search").param("q", "").param("language", "en").header(
            "Authorization", "Bearer " + token))
        .andExpect(status().isUnauthorized());

    // A token nobody issued is answered the same way, and one that is not even
    // shaped like one never reaches a lookup.
    mockMvc
        .perform(get("/api/v1/search").param("q", "").param("language", "en").header(
            "Authorization", "Bearer homeinv_sa_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(get("/api/v1/search").param("q", "").param("language", "en").header(
            "Authorization", "Bearer not-one-of-ours"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("is issued and revoked only with the second factor proved again")
  void issuingNeedsARecentSecondFactor() throws Exception {
    Fixture tenant = tenantWithOwner("sa-stepup@example.org");
    MockHttpSession owner = signIn("sa-stepup@example.org", PASSWORD);
    owner.setAttribute(
        de.greluc.homeinv.rest.SessionEstablisher.SECOND_FACTOR_AT,
        Instant.now().minusSeconds(16 * 60).getEpochSecond());

    mockMvc
        .perform(
            post("/api/v1/tenants/" + tenant.tenantId() + "/service-accounts")
                .session(owner)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "name", "Too late",
                            "role", "VIEWER",
                            "expiresAt", Instant.now().plus(1, ChronoUnit.DAYS).toString()))))
        .andExpect(status().isForbidden())
        .andExpect(
            jsonPath("$.type").value("https://home-inv.example/problems/second-factor-stale"));

    // Reading the list does not: it hands out nothing.
    mockMvc
        .perform(get("/api/v1/tenants/" + tenant.tenantId() + "/service-accounts").session(owner))
        .andExpect(status().isOk());
  }

  // -------------------------------------------------------------------------

  /**
   * Moves every token of a tenant past its expiry.
   *
   * @param tenantId the tenant
   */
  private void expire(UUID tenantId) {
    TenantContext.runAs(
        tenantId,
        () ->
            transactions.executeWithoutResult(
                status ->
                    jdbc.sql(
                            "update identity.service_account set expires_at ="
                                + " now() - interval '1 hour'")
                        .update()));
  }

  /** A tenant, its owner and a category to create a location with. */
  private record Fixture(UUID userId, UUID tenantId, UUID categoryId) {}

  private Fixture tenantWithOwner(String email) {
    UUID userId = UUID.randomUUID();
    transactions.executeWithoutResult(
        status ->
            users.save(
                AppUser.create(
                    userId, email, "Test", "en", passwordEncoder.encode(PASSWORD),
                    Instant.now())));
    // REQ-AUTH-003: an OWNER with no second factor is refused every request in
    // the tenant, and issuing a token is one.
    enrolSecondFactor(userId);
    UUID tenantId = provisioning.provision("Tenant of " + email, userId);
    UUID categoryId =
        TenantContext.callAs(
            tenantId,
            () ->
                transactions.execute(
                    status ->
                        jdbc.sql("select id from catalog.location_category order by key limit 1")
                            .query((rs, rowNum) -> rs.getObject(1, UUID.class))
                            .single()));
    return new Fixture(userId, tenantId, categoryId);
  }

  private static RequestPostProcessor csrf() {
    return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
        .csrf();
  }
}
