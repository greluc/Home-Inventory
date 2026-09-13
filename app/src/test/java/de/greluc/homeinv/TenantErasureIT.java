/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.homeinv.identity.api.AccountAdministration;
import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.tenancy.application.TenantErasureRunner;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.time.Instant;
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
 * Asking for a tenant to be erased, and changing one's mind (REQ-TEN-011, REQ-PRIV-005).
 *
 * <p>05 §5.9's first half: the state changes, access stops at once, the data stays, and the request
 * can be withdrawn by a link that works for somebody who cannot sign in — because the request is
 * exactly what stopped them from signing in.
 */
@DisplayName("Asking for a tenant to be erased")
class TenantErasureIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple-42";

  @Autowired private AppUserRepository users;
  @Autowired private AccountAdministration accounts;
  @Autowired private TenantErasureRunner runner;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private JdbcClient jdbc;
  @Autowired private ObjectMapper json;

  @Test
  @DisplayName("stops the tenant answering at once, and the link puts it back")
  void requestThenRevoke() throws Exception {
    Tenant tenant = tenantWithOwner("erase-owner@example.org", "Going");
    MockHttpSession owner = login("erase-owner@example.org");

    // Before: ordinary work is possible.
    mockMvc
        .perform(get("/api/v1/search").param("q", "").param("language", "en").session(owner))
        .andExpect(status().isOk());

    String requested =
        mockMvc
            .perform(delete("/api/v1/tenants/" + tenant.tenantId()).session(owner).with(csrf()))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.revocationToken").isNotEmpty())
            .andExpect(jsonPath("$.eraseAfter").isNotEmpty())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String token = json.readTree(requested).get("revocationToken").asString();

    // "Access blocked immediately" — the same session, the next request.
    mockMvc
        .perform(get("/api/v1/search").param("q", "").param("language", "en").session(owner))
        .andExpect(status().isForbidden())
        .andExpect(
            jsonPath("$.type").value("https://home-inv.example/problems/tenant-inaccessible"));

    // The state stays readable, because a member being refused everything else is
    // entitled to know why.
    mockMvc
        .perform(get("/api/v1/tenants/" + tenant.tenantId()).session(owner))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("PENDING_DELETION"));

    // Asking twice is a conflict carrying the date, not a second token.
    mockMvc
        .perform(delete("/api/v1/tenants/" + tenant.tenantId()).session(owner).with(csrf()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value("https://home-inv.example/problems/deletion-pending"))
        .andExpect(jsonPath("$.eraseAfter").isNotEmpty());

    // The link works with no session at all.
    mockMvc
        .perform(post("/api/v1/tenant-revocations/" + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(tenant.tenantId().toString()))
        .andExpect(jsonPath("$.state").value("ACTIVE"));

    // And the tenant answers again.
    mockMvc
        .perform(get("/api/v1/search").param("q", "").param("language", "en").session(owner))
        .andExpect(status().isOk());

    // The token is spent: a second use is answered like one that never existed.
    mockMvc
        .perform(post("/api/v1/tenant-revocations/" + token))
        .andExpect(status().isGone())
        .andExpect(
            jsonPath("$.type").value("https://home-inv.example/problems/revocation-unusable"));

    mockMvc
        .perform(post("/api/v1/tenant-revocations/this-token-never-existed"))
        .andExpect(status().isGone())
        .andExpect(
            jsonPath("$.type").value("https://home-inv.example/problems/revocation-unusable"));
  }

  @Test
  @DisplayName("is the owner's alone: an administrator may not start it")
  void onlyTheOwnerAsks() throws Exception {
    Tenant tenant = tenantWithOwner("erase-admin-owner@example.org", "Owned");
    addMember(tenant, "erase-admin@example.org", "ADMIN");

    mockMvc
        .perform(
            delete("/api/v1/tenants/" + tenant.tenantId())
                .session(login("erase-admin@example.org"))
                .with(csrf()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.type").value("https://home-inv.example/problems/forbidden"));

    // The tenant is untouched.
    mockMvc
        .perform(
            get("/api/v1/tenants/" + tenant.tenantId())
                .session(login("erase-admin@example.org")))
        .andExpect(jsonPath("$.state").value("ACTIVE"));
  }

  @Test
  @DisplayName("leaves a member's other tenants alone, and lets them switch away")
  void anotherTenantStillWorks() throws Exception {
    Tenant going = tenantWithOwner("erase-both@example.org", "Going");
    UUID owner = going.ownerId();
    UUID staying =
        TenantContext.callAs(
            going.tenantId(), () -> provisioning.provision("Staying", owner));

    MockHttpSession session = login("erase-both@example.org");
    mockMvc
        .perform(delete("/api/v1/tenants/" + going.tenantId()).session(session).with(csrf()))
        .andExpect(status().isAccepted());

    // Switching away is one of the three things a blocked tenant does not stop.
    mockMvc
        .perform(
            post("/api/v1/me/tenant")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("tenantId", staying.toString()))))
        .andExpect(status().isOk());

    mockMvc
        .perform(get("/api/v1/search").param("q", "").param("language", "en").session(session))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("the certificate it leaves is the operator's to read, and nobody else's")
  void theCertificateIsReadByTheOperator() throws Exception {
    Tenant tenant = tenantWithOwner("erase-certified@example.org", "Certified");
    MockHttpSession owner = login("erase-certified@example.org");

    mockMvc
        .perform(delete("/api/v1/tenants/" + tenant.tenantId()).session(owner).with(csrf()))
        .andExpect(status().isAccepted());

    // The request is backdated rather than the grace period shortened, so the
    // thirty days the requirement names stay the ones under test.
    backdate(tenant);
    runner.eraseDueTenants();

    // An ordinary account does not reach the instance surface at all.
    UUID ordinary = createUser("erase-onlooker@example.org");
    provisioning.provision("Onlooker", ordinary);
    mockMvc
        .perform(
            get("/api/v1/instance/erasures").session(login("erase-onlooker@example.org")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.type").value("https://home-inv.example/problems/forbidden"));

    MockHttpSession operator = operatorSession("erase-operator@example.org");
    mockMvc
        .perform(get("/api/v1/instance/erasures").session(operator))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.items[?(@.tenantId == '" + tenant.tenantId() + "')]").exists());

    mockMvc
        .perform(get("/api/v1/instance/erasures/" + tenant.tenantId()).session(operator))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tenantName").value("Certified"))
        .andExpect(jsonPath("$.requestedBy").value(tenant.ownerId().toString()))
        .andExpect(jsonPath("$.report[?(@.block == 'audit')].note").exists());

    // A tenant that was never erased has no certificate, and says so the way
    // every other unknown thing does.
    mockMvc
        .perform(get("/api/v1/instance/erasures/" + UUID.randomUUID()).session(operator))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("https://home-inv.example/problems/not-found"));
  }

  // -------------------------------------------------------------------------

  /**
   * Moves a request past its grace period.
   *
   * @param tenant whose request
   */
  private void backdate(Tenant tenant) {
    TenantContext.runAs(
        tenant.tenantId(),
        () ->
            transactions.executeWithoutResult(
                status ->
                    jdbc.sql(
                            "update tenancy.tenant set deletion_requested_at ="
                                + " now() - interval '31 days'")
                        .update()));
  }

  /**
   * An account that administers the instance, signed in.
   *
   * @param email its login address
   * @return its session
   * @throws Exception when signing in fails, which is the test failing
   */
  private MockHttpSession operatorSession(String email) throws Exception {
    UUID userId = createUser(email);
    provisioning.provision("The operator's own", userId);
    transactions.executeWithoutResult(
        status -> accounts.replaceEntitlements(userId, true, false, null, userId));
    return login(email);
  }

  /** A provisioned tenant and the user who owns it. */
  private record Tenant(UUID tenantId, UUID ownerId) {}

  private Tenant tenantWithOwner(String email, String tenantName) {
    UUID userId = createUser(email);
    return new Tenant(provisioning.provision(tenantName, userId), userId);
  }

  private UUID addMember(Tenant tenant, String email, String role) {
    UUID userId = createUser(email);
    TenantContext.runAs(
        tenant.tenantId(),
        () ->
            transactions.executeWithoutResult(
                status ->
                    jdbc.sql(
                            "insert into tenancy.membership (id, tenant_id, user_id, role,"
                                + " created_at, updated_at, version)"
                                + " values (?, ?, ?, ?, now(), now(), 1)")
                        .params(UUID.randomUUID(), tenant.tenantId(), userId, role)
                        .update()));
    return userId;
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

  private MockHttpSession login(String email) throws Exception {
    return signIn(email, PASSWORD);
  }

  private static RequestPostProcessor csrf() {
    return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
        .csrf();
  }
}
