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
import java.util.List;
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
 * Roles a tenant defines for itself, and what they are allowed to be (REQ-TEN-006, REQ-TEN-010).
 *
 * <p>Through HTTP, because a role is only worth anything once it is assigned and evaluated: what is
 * being tested is that a definition written through one endpoint changes what a different session
 * may do through another.
 */
@DisplayName("Tenant-owned roles")
class TenantOwnedRolesIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple-42";

  @Autowired private AppUserRepository users;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private JdbcClient jdbc;
  @Autowired private ObjectMapper json;

  @Test
  @DisplayName("extend a built-in role, and what they add takes effect at once")
  void aCustomRoleGrantsWhatItAdds() throws Exception {
    Tenant tenant = tenantWithOwner("role-owner@example.org", "Defining");
    MockHttpSession owner = login("role-owner@example.org");
    UUID helper = addMember(tenant, "role-helper@example.org", "CONTRIBUTOR", null);

    // A CONTRIBUTOR may not delete an item. The role below adds exactly that.
    UUID roleId =
        UUID.fromString(
            json.readTree(
                    mockMvc
                        .perform(
                            post("/api/v1/roles")
                                .session(owner)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                    json.writeValueAsString(
                                        Map.of(
                                            "name", "Stocktaker",
                                            "baseRole", "CONTRIBUTOR",
                                            "permissions",
                                                List.of("inventory:item:delete")))))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.baseRole").value("CONTRIBUTOR"))
                        // The effective set is what a client shows, so it does not
                        // have to re-implement the ladder.
                        .andExpect(
                            jsonPath("$.effectivePermissions")
                                .value(org.hamcrest.Matchers.hasItem("inventory:item:create")))
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("id")
                .asString());

    mockMvc
        .perform(
            put("/api/v1/tenants/" + tenant.tenantId() + "/members/" + helper)
                .session(owner)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("role", "CONTRIBUTOR", "roleDefinitionId", roleId.toString()))))
        .andExpect(status().isOk())
        // The member list shows the role's NAME, not its id.
        .andExpect(jsonPath("$.roleName").value("Stocktaker"));

    // The session established after the assignment holds the added permission.
    MockHttpSession helperSession = login("role-helper@example.org");
    String created =
        mockMvc
            .perform(
                post("/api/v1/items")
                    .session(helperSession)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("name", "A thing", "kind", "DIGITAL"))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID itemId = UUID.fromString(json.readTree(created).get("id").asString());

    mockMvc
        .perform(delete("/api/v1/items/" + itemId).session(helperSession).with(csrf()))
        .andExpect(status().isNoContent());

    // Take the permission away again, and the SAME session loses it: an
    // entitlement of a role is read from the definition, not carried in the
    // principal, so a change reaches somebody who is signed in while it happens.
    mockMvc
        .perform(
            put("/api/v1/roles/" + roleId)
                .session(owner)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "name", "Stocktaker",
                            "baseRole", "CONTRIBUTOR",
                            "permissions", List.of()))))
        .andExpect(status().isOk());

    String second =
        mockMvc
            .perform(
                post("/api/v1/items")
                    .session(helperSession)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("name", "Another", "kind", "DIGITAL"))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

    mockMvc
        .perform(
            delete("/api/v1/items/" + json.readTree(second).get("id").asString())
                .session(helperSession)
                .with(csrf()))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("cannot add a permission the person defining them does not hold (REQ-TEN-010)")
  void noEscalationThroughADefinition() throws Exception {
    Tenant tenant = tenantWithOwner("role-esc-owner@example.org", "Escalating");
    addMember(tenant, "role-esc-member@example.org", "MEMBER", null);

    // A MEMBER holds the content band and not the type system. Defining a role
    // that adds it would be a way to exercise it through somebody else — which is
    // exactly what the requirement forbids. They cannot reach the endpoint at all,
    // because defining a role IS deciding who may do what.
    mockMvc
        .perform(
            post("/api/v1/roles")
                .session(login("role-esc-member@example.org"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "name", "Sneaky",
                            "baseRole", "MEMBER",
                            "permissions", List.of("catalog:type:create")))))
        .andExpect(status().isForbidden());

    // An ADMIN may define roles, and may not start one from OWNER: ownership is a
    // relationship rather than a permission set.
    addMember(tenant, "role-esc-admin@example.org", "ADMIN", null);
    mockMvc
        .perform(
            post("/api/v1/roles")
                .session(login("role-esc-admin@example.org"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("name", "Almost owner", "baseRole", "OWNER", "permissions",
                            List.of()))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.type").value("https://home-inv.example/problems/role-escalation"));
  }

  @Test
  @DisplayName("keep their name to themselves, and removing one demotes its holders to its base")
  void namesAndRemoval() throws Exception {
    Tenant tenant = tenantWithOwner("role-name@example.org", "Naming");
    MockHttpSession owner = login("role-name@example.org");

    String body =
        json.writeValueAsString(
            Map.of("name", "Warehouse", "baseRole", "VIEWER", "permissions", List.of()));
    String created =
        mockMvc
            .perform(
                post("/api/v1/roles")
                    .session(owner)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID roleId = UUID.fromString(json.readTree(created).get("id").asString());

    // Case-insensitively unique, like every other name here: two roles called
    // "Warehouse" and "warehouse" are a way to grant one and revoke the other.
    mockMvc
        .perform(
            post("/api/v1/roles")
                .session(owner)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("name", "warehouse", "baseRole", "VIEWER", "permissions",
                            List.of()))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value("https://home-inv.example/problems/name-taken"));

    UUID member = addMember(tenant, "role-name-member@example.org", "VIEWER", roleId);

    mockMvc
        .perform(delete("/api/v1/roles/" + roleId).session(owner).with(csrf()))
        .andExpect(status().isNoContent());

    // The membership survives and falls back to the base the definition extended.
    mockMvc
        .perform(get("/api/v1/tenants/" + tenant.tenantId() + "/members").session(owner))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.items[?(@.userId == '" + member + "')].roleName")
                .value(org.hamcrest.Matchers.hasItem("VIEWER")));

    // And the name is free again.
    mockMvc
        .perform(
            post("/api/v1/roles")
                .session(owner)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isCreated());
  }

  // -------------------------------------------------------------------------

  /** A provisioned tenant and the user who owns it. */
  private record Tenant(UUID tenantId, UUID ownerId) {}

  private Tenant tenantWithOwner(String email, String tenantName) {
    UUID userId = createUser(email);
    return new Tenant(provisioning.provision(tenantName, userId), userId);
  }

  /**
   * Puts a second person in a tenant, optionally on a tenant-owned role.
   *
   * @param tenant the tenant
   * @param email the person's address
   * @param role the built-in role
   * @param roleDefinitionId the tenant-owned role extending it, or null
   * @return the new member's account id
   */
  private UUID addMember(Tenant tenant, String email, String role, UUID roleDefinitionId) {
    UUID userId = createUser(email);
    TenantContext.runAs(
        tenant.tenantId(),
        () ->
            transactions.executeWithoutResult(
                status ->
                    jdbc.sql(
                            "insert into tenancy.membership (id, tenant_id, user_id, role,"
                                + " role_definition_id, created_at, updated_at, version)"
                                + " values (?, ?, ?, ?, ?, now(), now(), 1)")
                        .params(
                            UUID.randomUUID(), tenant.tenantId(), userId, role, roleDefinitionId)
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
