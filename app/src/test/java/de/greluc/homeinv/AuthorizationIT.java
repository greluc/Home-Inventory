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
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * The default is deny, and a role decides what a session may do (REQ-SEC-023…025).
 *
 * <p>Driven through the whole chain — login, session, interceptor, application layer — because that
 * is the only way to establish that the role in the session is the one being evaluated. A unit test
 * of {@code DefaultAccessControl} proves the ladder; only this proves it is wired to anything.
 */
@DisplayName("Permissions")
class AuthorizationIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple-42";

  @Autowired private AppUserRepository users;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private JdbcClient jdbc;
  @Autowired private ObjectMapper json;

  @Test
  @DisplayName("a VIEWER may read and search")
  void aViewerMayRead() throws Exception {
    Tenant tenant = tenantWithOwner("owner-read@example.org", "Reading");
    String viewer = addMember(tenant, "viewer-read@example.org", "VIEWER");

    MockHttpSession session = login(viewer);

    mockMvc
        .perform(get("/api/v1/search").param("q", "").param("language", "de").session(session))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("a VIEWER may not create, and is told so as a permission problem")
  void aViewerMayNotCreate() throws Exception {
    Tenant tenant = tenantWithOwner("owner-create@example.org", "Creating");
    String viewer = addMember(tenant, "viewer-create@example.org", "VIEWER");

    MockHttpSession session = login(viewer);

    mockMvc
        .perform(
            post("/api/v1/items")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", "A thing", "kind", "DIGITAL"))))
        // 403 and not 404: the caller is a member of this tenant and the fact
        // that items exist is not a secret from them. Only a resource in another
        // tenant is a 404 (REQ-SEC-025).
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.type").value("https://home-inv.example/problems/forbidden"))
        // The detail names no resource and no permission: it is read by a person,
        // and the permission id belongs in the log, not in a response body.
        .andExpect(jsonPath("$.detail").value("Your role does not permit this operation."));
  }

  @Test
  @DisplayName("a GUEST may not search, because a search surface is an enumeration surface")
  void aGuestMayNotSearch() throws Exception {
    Tenant tenant = tenantWithOwner("owner-guest@example.org", "Guesting");
    String guest = addMember(tenant, "guest@example.org", "GUEST");

    MockHttpSession session = login(guest);

    mockMvc
        .perform(get("/api/v1/search").param("q", "").param("language", "de").session(session))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("a CONTRIBUTOR may add but not delete")
  void aContributorMayNotDelete() throws Exception {
    Tenant tenant = tenantWithOwner("owner-contrib@example.org", "Contributing");
    String contributor = addMember(tenant, "contributor@example.org", "CONTRIBUTOR");

    MockHttpSession session = login(contributor);

    String created =
        mockMvc
            .perform(
                post("/api/v1/items")
                    .session(session)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(Map.of("name", "A note", "kind", "DIGITAL"))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

    UUID id = UUID.fromString(json.readTree(created).get("id").asString());

    mockMvc
        .perform(delete("/api/v1/items/" + id).session(session).with(csrf()))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("an OWNER may do everything this stage defines")
  void anOwnerMayDoEverything() throws Exception {
    Tenant tenant = tenantWithOwner("owner-all@example.org", "Owning");
    MockHttpSession session = login("owner-all@example.org");

    String created =
        mockMvc
            .perform(
                post("/api/v1/items")
                    .session(session)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("name", "Mine", "kind", "DIGITAL"))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

    UUID id = UUID.fromString(json.readTree(created).get("id").asString());

    mockMvc
        .perform(
            delete("/api/v1/items/" + id)
                .session(session)
                .with(csrf())
                // REQ-API-004: every write on a single resource says which version
                // it acted on, including the ones whose subject is a permission.
                .header("If-Match", eTagOf(session, "/api/v1/items/" + id)))
        .andExpect(status().isNoContent());

    assertThat(tenant.tenantId()).isNotNull();
  }

  @Test
  @DisplayName("the session reports the role, so a client can decide what to offer")
  void theSessionCarriesTheRole() throws Exception {
    Tenant tenant = tenantWithOwner("owner-role@example.org", "Roles");
    String viewer = addMember(tenant, "viewer-role@example.org", "VIEWER");

    mockMvc
        .perform(get("/api/v1/auth/me").session(login(viewer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.role").value("VIEWER"));
  }

  // -------------------------------------------------------------------------

  /** A provisioned tenant and the user who owns it. */
  private record Tenant(UUID tenantId, UUID ownerId) {}

  private Tenant tenantWithOwner(String email, String tenantName) {
    UUID userId = createUser(email);
    return new Tenant(provisioning.provision(tenantName, userId), userId);
  }

  /**
   * Adds a second member to an existing tenant, in a role stage 0 never writes itself.
   *
   * <p>Direct SQL, under the tenant context, because provisioning only ever creates an {@code
   * OWNER} — stage 0 has no member administration (that is {@code REQ-TEN-004}, stage 1). The
   * ladder exists in code now and has to be exercisable now, or it ships untested and the first
   * time anybody uses {@code VIEWER} is in production.
   */
  private String addMember(Tenant tenant, String email, String role) {
    UUID userId = createUser(email);
    // The context is set OUTSIDE the transaction, not inside it. The transaction
    // manager pushes `app.tenant_id` into the database session when the
    // transaction BEGINS, so a context established inside the callback is set
    // after the connection has already been configured - and the insert is
    // refused by the very policy this test depends on.
    TenantContext.runAs(
        tenant.tenantId(),
        () ->
            transactions.executeWithoutResult(
                status ->
                    jdbc.sql(
                            "insert into tenancy.membership "
                                + "(id, tenant_id, user_id, role, created_at, updated_at, version) "
                                + "values (?, ?, ?, ?, now(), now(), 1)")
                        .params(UUID.randomUUID(), tenant.tenantId(), userId, role)
                        .update()));
    return email;
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
                    "de",
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

  private static org.springframework.test.web.servlet.request.RequestPostProcessor csrf() {
    return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
        .csrf();
  }
}
