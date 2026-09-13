/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Invitations, member administration and the rule that nobody grants what they do not hold
 * (REQ-TEN-004, REQ-TEN-005, REQ-TEN-010).
 *
 * <p>Through HTTP end to end, because the invitation flow is the one place where a tenant context
 * is established from something other than a session, and a service-level test would establish it
 * by hand and prove nothing about that.
 */
@DisplayName("Members and invitations")
class MembersAndInvitationsIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple-42";

  @Autowired private AppUserRepository users;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private JdbcClient jdbc;
  @Autowired private ObjectMapper json;

  @Test
  @DisplayName("an invitation makes an account and a membership, once (REQ-TEN-004)")
  void inviteAndAccept() throws Exception {
    Tenant tenant = tenantWithOwner("inv-owner@example.org", "Invited into");
    MockHttpSession owner = login("inv-owner@example.org");

    String token = invite(tenant, owner, "inv-newcomer@example.org", "MEMBER");

    mockMvc
        .perform(
            post("/api/v1/invitations/" + token + "/accept")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("displayName", "Newcomer", "locale", "en", "password", PASSWORD))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tenantId").value(tenant.tenantId().toString()))
        .andExpect(jsonPath("$.role").value("MEMBER"))
        .andExpect(jsonPath("$.accountCreated").value(true));

    // The account the acceptance created can sign in, and lands in the tenant.
    mockMvc
        .perform(get("/api/v1/auth/me").session(login("inv-newcomer@example.org")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tenantId").value(tenant.tenantId().toString()))
        .andExpect(jsonPath("$.role").value("MEMBER"));

    // Single use. The second attempt is answered identically to a token that
    // never existed, so the difference cannot be read off the response.
    mockMvc
        .perform(
            post("/api/v1/invitations/" + token + "/accept")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("password", PASSWORD))))
        .andExpect(status().isGone())
        .andExpect(
            jsonPath("$.type").value("https://home-inv.example/problems/invitation-unusable"));

    mockMvc
        .perform(
            post("/api/v1/invitations/this-token-never-existed/accept")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("password", PASSWORD))))
        .andExpect(status().isGone())
        .andExpect(
            jsonPath("$.type").value("https://home-inv.example/problems/invitation-unusable"));
  }

  @Test
  @DisplayName("an expired invitation opens nothing (REQ-TEN-004)")
  void expiry() throws Exception {
    Tenant tenant = tenantWithOwner("inv-expiry@example.org", "Expiring");
    String token = invite(tenant, login("inv-expiry@example.org"), "inv-late@example.org", "VIEWER");

    // Moved into the past directly: the alternative is a clock the whole context
    // shares, and a test that changes time for every other test in the suite.
    TenantContext.runAs(
        tenant.tenantId(),
        () ->
            transactions.executeWithoutResult(
                status ->
                    jdbc.sql(
                            "update tenancy.invitation set expires_at = now() - interval '1 day' "
                                + "where tenant_id = ?")
                        .param(tenant.tenantId())
                        .update()));

    mockMvc
        .perform(
            post("/api/v1/invitations/" + token + "/accept")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("password", PASSWORD))))
        .andExpect(status().isGone());
  }

  @Test
  @DisplayName("a withdrawn invitation opens nothing (REQ-TEN-004)")
  void revocation() throws Exception {
    Tenant tenant = tenantWithOwner("inv-revoke@example.org", "Withdrawing");
    MockHttpSession owner = login("inv-revoke@example.org");
    String token = invite(tenant, owner, "inv-unwanted@example.org", "VIEWER");

    String listed =
        mockMvc
            .perform(get("/api/v1/tenants/" + tenant.tenantId() + "/invitations").session(owner))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].state").value("OPEN"))
            // The token is never in a listing: it exists once, in the answer that
            // created it, and afterwards only as a hash.
            .andExpect(jsonPath("$.items[0].token").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String invitationId = json.readTree(listed).get("items").get(0).get("id").asString();

    mockMvc
        .perform(
            delete("/api/v1/tenants/" + tenant.tenantId() + "/invitations/" + invitationId)
                .session(owner)
                .with(csrf()))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(
            post("/api/v1/invitations/" + token + "/accept")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("password", PASSWORD))))
        .andExpect(status().isGone());
  }

  @Test
  @DisplayName("an address with an account needs that account to accept")
  void acceptingForSomebodyElse() throws Exception {
    Tenant tenant = tenantWithOwner("inv-host@example.org", "Hosting");
    createUser("inv-existing@example.org");

    String token = invite(tenant, login("inv-host@example.org"), "inv-existing@example.org", "VIEWER");

    // Nobody signed in: the token proves the mailbox, which is not enough to put
    // a membership on an account that already belongs to somebody.
    mockMvc
        .perform(
            post("/api/v1/invitations/" + token + "/accept")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("password", PASSWORD))))
        .andExpect(status().isForbidden())
        .andExpect(
            jsonPath("$.type").value("https://home-inv.example/problems/invitation-not-yours"));

    // Signed in as somebody else: the same answer.
    mockMvc
        .perform(
            post("/api/v1/invitations/" + token + "/accept")
                .session(login("inv-host@example.org"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("password", PASSWORD))))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("nobody grants a role they do not hold themselves (REQ-TEN-010)")
  void noEscalation() throws Exception {
    Tenant tenant = tenantWithOwner("esc-owner@example.org", "Escalating");
    UUID admin = addMember(tenant, "esc-admin@example.org", "ADMIN");
    UUID member = addMember(tenant, "esc-member@example.org", "MEMBER");

    MockHttpSession adminSession = login("esc-admin@example.org");

    // ADMIN and OWNER hold the same permissions today, so the permission test
    // alone would let this through. Ownership is a relationship, not a set.
    mockMvc
        .perform(
            put("/api/v1/tenants/" + tenant.tenantId() + "/members/" + member)
                .session(adminSession)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("role", "OWNER"))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.type").value("https://home-inv.example/problems/role-escalation"))
        .andExpect(jsonPath("$.actorRole").value("ADMIN"))
        .andExpect(jsonPath("$.targetRole").value("OWNER"));

    // Nor may an admin remove the owner, which is the same escalation backwards.
    mockMvc
        .perform(
            delete("/api/v1/tenants/" + tenant.tenantId() + "/members/" + tenant.ownerId())
                .session(adminSession)
                .with(csrf()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.type").value("https://home-inv.example/problems/role-escalation"));

    // A MEMBER may not administer members at all — that is a permission, and it
    // is refused before any of the above is reached.
    mockMvc
        .perform(
            put("/api/v1/tenants/" + tenant.tenantId() + "/members/" + admin)
                .session(login("esc-member@example.org"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("role", "VIEWER"))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.type").value("https://home-inv.example/problems/forbidden"));

    // What an admin may do: anything up to their own rung.
    mockMvc
        .perform(
            put("/api/v1/tenants/" + tenant.tenantId() + "/members/" + member)
                .session(adminSession)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("role", "CONTRIBUTOR"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.role").value("CONTRIBUTOR"))
        .andExpect(jsonPath("$.email").value("esc-member@example.org"));
  }

  @Test
  @DisplayName("a tenant keeps at least one owner")
  void theLastOwnerStays() throws Exception {
    Tenant tenant = tenantWithOwner("last-owner@example.org", "Owned");
    MockHttpSession owner = login("last-owner@example.org");
    UUID second = addMember(tenant, "last-member@example.org", "MEMBER");

    mockMvc
        .perform(
            put("/api/v1/tenants/" + tenant.tenantId() + "/members/" + tenant.ownerId())
                .session(owner)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("role", "MEMBER"))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value("https://home-inv.example/problems/last-owner"));

    // With a second owner in place, stepping down is allowed.
    mockMvc
        .perform(
            put("/api/v1/tenants/" + tenant.tenantId() + "/members/" + second)
                .session(owner)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("role", "OWNER"))))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            put("/api/v1/tenants/" + tenant.tenantId() + "/members/" + tenant.ownerId())
                .session(owner)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("role", "MEMBER"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.role").value("MEMBER"));
  }

  @Test
  @DisplayName("a removed member loses the tenant and can be invited back")
  void removalIsATombstone() throws Exception {
    Tenant tenant = tenantWithOwner("rm-owner@example.org", "Removing");
    MockHttpSession owner = login("rm-owner@example.org");
    UUID member = addMember(tenant, "rm-member@example.org", "MEMBER");

    mockMvc
        .perform(get("/api/v1/tenants/" + tenant.tenantId() + "/members").session(owner))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2));

    mockMvc
        .perform(
            delete("/api/v1/tenants/" + tenant.tenantId() + "/members/" + member)
                .session(owner)
                .with(csrf()))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(get("/api/v1/tenants/" + tenant.tenantId() + "/members").session(owner))
        .andExpect(jsonPath("$.items.length()").value(1));

    // Removing twice is not an error, for the reason deleting twice is not.
    mockMvc
        .perform(
            delete("/api/v1/tenants/" + tenant.tenantId() + "/members/" + member)
                .session(owner)
                .with(csrf()))
        .andExpect(status().isNoContent());

    // The tombstone does not block a second invitation to the same person.
    String token = invite(tenant, owner, "rm-member@example.org", "VIEWER");
    mockMvc
        .perform(
            post("/api/v1/invitations/" + token + "/accept")
                .session(login("rm-member@example.org"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("password", PASSWORD))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accountCreated").value(false));
  }

  @Test
  @DisplayName("a path naming another tenant is answered as though it did not exist")
  void aForeignTenantInThePath() throws Exception {
    Tenant mine = tenantWithOwner("path-mine@example.org", "Mine");
    Tenant theirs = tenantWithOwner("path-theirs@example.org", "Theirs");

    mockMvc
        .perform(get("/api/v1/tenants/" + theirs.tenantId() + "/members").session(login("path-mine@example.org")))
        .andExpect(status().isNotFound());

    mockMvc.perform(get("/api/v1/tenants/" + mine.tenantId() + "/members").session(login("path-mine@example.org")))
        .andExpect(status().isOk());
  }

  // -------------------------------------------------------------------------

  /** A provisioned tenant and the user who owns it. */
  private record Tenant(UUID tenantId, UUID ownerId) {}

  /**
   * Issues an invitation and returns its token.
   *
   * @param tenant the tenant
   * @param session the inviter's session
   * @param email the address to invite
   * @param role the role to grant
   * @return the one copy of the token
   * @throws Exception when the request fails
   */
  private String invite(Tenant tenant, MockHttpSession session, String email, String role)
      throws Exception {
    String body =
        mockMvc
            .perform(
                post("/api/v1/tenants/" + tenant.tenantId() + "/invitations")
                    .session(session)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("email", email, "role", role))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(body).get("token").asString();
  }

  private Tenant tenantWithOwner(String email, String tenantName) {
    UUID userId = createUser(email);
    return new Tenant(provisioning.provision(tenantName, userId), userId);
  }

  /**
   * Puts a second person in a tenant, in a role the provisioning never writes.
   *
   * @param tenant the tenant
   * @param email the person's address
   * @param role the role to give them
   * @return the new member's account id
   */
  private UUID addMember(Tenant tenant, String email, String role) {
    UUID userId = createUser(email);
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
