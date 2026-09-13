/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.homeinv.authorization.api.AccountEntitlements;
import de.greluc.homeinv.authorization.api.Entitlement;
import de.greluc.homeinv.identity.api.AccountAdministration;
import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Several tenants per person, the entitlement that lets them exist, and switching between them
 * (REQ-TEN-002, REQ-TEN-003, ADR-0057).
 *
 * <p>Driven through HTTP, because the parts being tested only exist together: the entitlement is
 * checked by an interceptor, the switch rewrites a stored security context, and the tenant context
 * every later query runs under comes from that principal. A service-level test would prove the
 * pieces and none of the wiring.
 */
@DisplayName("Tenants, entitlements and switching")
class TenancyIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple-42";

  @Autowired private AppUserRepository users;
  @Autowired private AccountAdministration accounts;
  @Autowired private AccountEntitlements entitlements;
  @Autowired private org.springframework.jdbc.core.simple.JdbcClient jdbc;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private ObjectMapper json;

  @Test
  @DisplayName("an account without the entitlement cannot create one (REQ-TEN-002)")
  void withoutTheEntitlement() throws Exception {
    UUID userId = createUser("ten-none@example.org");
    provisioning.provision("First", userId);

    mockMvc
        .perform(
            post("/api/v1/tenants")
                .session(login("ten-none@example.org"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", "Second"))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.type").value("https://home-inv.example/problems/forbidden"));
  }

  @Test
  @DisplayName("an entitled account creates one and owns it (REQ-TEN-002)")
  void withTheEntitlement() throws Exception {
    UUID userId = createUser("ten-entitled@example.org");
    provisioning.provision("First", userId);
    entitle(userId, null);

    mockMvc
        .perform(
            post("/api/v1/tenants")
                .session(login("ten-entitled@example.org"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", "Workshop"))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name").value("Workshop"))
        .andExpect(jsonPath("$.role").value("OWNER"));

    // The session is NOT moved: creating a tenant must not lose whatever the
    // person had open in the one they were working in.
    mockMvc
        .perform(get("/api/v1/me/tenants").session(login("ten-entitled@example.org")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].name").value("First"))
        .andExpect(jsonPath("$[1].name").value("Workshop"));
  }

  @Test
  @DisplayName("the quota refuses the next one, with both numbers (REQ-TEN-002, REQ-TEN-009)")
  void theQuotaBites() throws Exception {
    UUID userId = createUser("ten-quota@example.org");
    provisioning.provision("Only", userId);
    entitle(userId, 1);

    mockMvc
        .perform(
            post("/api/v1/tenants")
                .session(login("ten-quota@example.org"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", "One too many"))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.type").value("https://home-inv.example/problems/quota-exceeded"))
        .andExpect(jsonPath("$.current").value(1))
        .andExpect(jsonPath("$.permitted").value(1));
  }

  @Test
  @DisplayName("a person switches without signing in again (REQ-TEN-003)")
  void switching() throws Exception {
    UUID userId = createUser("ten-switch@example.org");
    UUID first = provisioning.provision("Home", userId);
    entitle(userId, null);

    MockHttpSession session = login("ten-switch@example.org");

    String created =
        mockMvc
            .perform(
                post("/api/v1/tenants")
                    .session(session)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("name", "Club"))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID second = UUID.fromString(json.readTree(created).get("id").asString());

    // The session still acts for the tenant it was established in.
    mockMvc
        .perform(get("/api/v1/auth/me").session(session))
        .andExpect(jsonPath("$.tenantId").value(first.toString()));

    mockMvc
        .perform(
            post("/api/v1/me/tenant")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("tenantId", second.toString()))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tenantId").value(second.toString()))
        .andExpect(jsonPath("$.role").value("OWNER"));

    // And the change outlives the request that made it: the next one reads the
    // rewritten principal out of the session rather than the original.
    mockMvc
        .perform(get("/api/v1/auth/me").session(session))
        .andExpect(jsonPath("$.tenantId").value(second.toString()));
  }

  @Test
  @DisplayName("a tenant the caller is not in is answered as though it did not exist")
  void switchingSomewhereElse() throws Exception {
    UUID mine = createUser("ten-mine@example.org");
    provisioning.provision("Mine", mine);
    UUID theirs = createUser("ten-theirs@example.org");
    UUID foreign = provisioning.provision("Theirs", theirs);

    mockMvc
        .perform(
            post("/api/v1/me/tenant")
                .session(login("ten-mine@example.org"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("tenantId", foreign.toString()))))
        // 404 and not 403: a denial would confirm that the tenant exists, which
        // for somebody outside it is the fact they were not supposed to learn.
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("https://home-inv.example/problems/not-found"));
  }

  @Test
  @DisplayName("only the operator may grant, and the grant takes effect at once (ADR-0057)")
  void onlyTheOperatorGrants() throws Exception {
    UUID operator = createUser("ten-operator@example.org");
    provisioning.provision("Operator's own", operator);
    UUID ordinary = createUser("ten-ordinary@example.org");
    provisioning.provision("Ordinary", ordinary);

    // An ordinary account cannot reach the instance surface at all.
    mockMvc
        .perform(
            get("/api/v1/instance/accounts")
                .param("email", "ten-ordinary@example.org")
                .session(login("ten-ordinary@example.org")))
        .andExpect(status().isForbidden());

    transactions.executeWithoutResult(
        status -> accounts.replaceEntitlements(operator, true, false, null, operator));

    MockHttpSession operatorSession = login("ten-operator@example.org");
    mockMvc
        .perform(
            put("/api/v1/instance/accounts/" + ordinary + "/entitlements")
                .session(operatorSession)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("instanceOperator", false, "mayCreateTenants", true))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mayCreateTenants").value(true))
        .andExpect(jsonPath("$.instanceOperator").value(false));

    // The session that was already open gets the new entitlement without being
    // re-established: an entitlement is read from the account, not from the
    // principal, precisely so that a grant an operator watches actually happens.
    mockMvc
        .perform(
            post("/api/v1/tenants")
                .session(login("ten-ordinary@example.org"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", "Newly allowed"))))
        .andExpect(status().isCreated());

    // And the operator appears in the list of who can do this.
    mockMvc
        .perform(get("/api/v1/instance/operators").session(operatorSession))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[?(@.email == 'ten-operator@example.org')]").exists());
  }

  @Test
  @DisplayName("a locked account holds nothing, whatever its columns say")
  void lockingRevokesEverything() {
    UUID userId = createUser("ten-locked@example.org");
    transactions.executeWithoutResult(
        status -> accounts.replaceEntitlements(userId, true, true, null, userId));
    assertThat(entitlements.holds(userId, Entitlement.INSTANCE_OPERATOR)).isTrue();

    // Written directly, because locking an account is an operator action that
    // does not exist yet. What is being tested is the read: an entitlement that
    // survived a lock would be one the lock does not reach, including the one
    // that grants entitlements.
    transactions.executeWithoutResult(
        status ->
            jdbc.sql("update identity.app_user set locked_at = now() where id = ?")
                .param(userId)
                .update());

    assertThat(entitlements.holds(userId, Entitlement.INSTANCE_OPERATOR)).isFalse();
    assertThat(entitlements.holds(userId, Entitlement.CREATE_TENANT)).isFalse();
    assertThat(accounts.byId(userId)).get().extracting(AccountAdministration.AccountView::locked)
        .isEqualTo(true);
  }

  // -------------------------------------------------------------------------

  /**
   * Grants the tenant-creation entitlement, with an optional limit.
   *
   * @param userId the account
   * @param limit the per-account limit, or null for the instance-wide default
   */
  private void entitle(UUID userId, Integer limit) {
    transactions.executeWithoutResult(
        status -> accounts.replaceEntitlements(userId, false, true, limit, userId));
  }

  /**
   * Creates an account with the shared test password.
   *
   * @param email the address
   * @return the new account's id
   */
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

  /**
   * Signs in and returns the session.
   *
   * @param email the address
   * @return a session carrying the established principal
   * @throws Exception when the login fails
   */
  private MockHttpSession login(String email) throws Exception {
    return signIn(email, PASSWORD);
  }

  private static RequestPostProcessor csrf() {
    return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
        .csrf();
  }
}
