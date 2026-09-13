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

import de.greluc.homeinv.catalog.api.FieldDataType;
import de.greluc.homeinv.catalog.api.TypeAdministration;
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
 * A sensitive field is removed for roles that may not read it (REQ-TEN-008, REQ-SEC-027).
 *
 * <p><b>Removed and not masked</b>, which is what the assertions check: the key is absent from the
 * answer, because a mask says the field exists and how long its value is — and for a purchase price
 * that is most of what somebody was after.
 *
 * <p>Every path that hands attributes to a client is exercised, because the one that is forgotten
 * is the one that leaks: the single item, the listing, and the revision history, whose snapshots are
 * stored whole so that a restore can put the real value back.
 */
@DisplayName("Sensitive fields")
class FieldVisibilityIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple-42";

  @Autowired private TypeAdministration types;
  @Autowired private AppUserRepository users;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private JdbcClient jdbc;
  @Autowired private ObjectMapper json;

  @Test
  @DisplayName("are removed for a role that may not read them, everywhere they would be shown")
  void removedNotMasked() throws Exception {
    Tenant tenant = tenantWithOwner("fv-owner@example.org", "Sensitive");
    MockHttpSession owner = login("fv-owner@example.org");
    UUID typeId = aTypeWithAPurchasePrice(tenant);
    addMember(tenant, "fv-member@example.org", "MEMBER");

    String created =
        mockMvc
            .perform(
                post("/api/v1/items")
                    .session(owner)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "name", "A drill",
                                "kind", "DIGITAL",
                                "itemTypeId", typeId.toString(),
                                "attributes", "{\"purchasePrice\":\"249.00\",\"colour\":\"blue\"}"))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID itemId = UUID.fromString(json.readTree(created).get("id").asString());

    // The owner reads everything, which is the default when no rule has been made.
    mockMvc
        .perform(get("/api/v1/items/" + itemId).session(owner))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.attributes").value(org.hamcrest.Matchers.containsString("249.00")));

    MockHttpSession member = login("fv-member@example.org");

    // A MEMBER does not, and the key is gone rather than starred out.
    mockMvc
        .perform(get("/api/v1/items/" + itemId).session(member))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.attributes")
                .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("249.00"))))
        .andExpect(
            jsonPath("$.attributes")
                .value(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("purchasePrice"))))
        // What is not sensitive is untouched.
        .andExpect(jsonPath("$.attributes").value(org.hamcrest.Matchers.containsString("blue")));

    // The listing is the path that shows the most attributes at once.
    mockMvc
        .perform(get("/api/v1/search").param("q", "").param("language", "en").session(member))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.items[*].attributes")
                .value(
                    org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString("purchasePrice")))));

    // And the history, whose snapshots are stored whole.
    mockMvc
        .perform(get("/api/v1/items/" + itemId + "/revisions").session(member))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.items[*].snapshot")
                .value(
                    org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("249.00")))));

    // The owner's history still has it: what is stored is complete, and a restore
    // has to be able to put the real value back.
    mockMvc
        .perform(get("/api/v1/items/" + itemId + "/revisions").session(owner))
        .andExpect(
            jsonPath("$.items[0].snapshot")
                .value(org.hamcrest.Matchers.containsString("249.00")));
  }

  @Test
  @DisplayName("become readable when a rule grants them, and stop again when it is withdrawn")
  void aRuleOpensAndCloses() throws Exception {
    Tenant tenant = tenantWithOwner("fv-rule-owner@example.org", "Granting");
    MockHttpSession owner = login("fv-rule-owner@example.org");
    UUID typeId = aTypeWithAPurchasePrice(tenant);
    addMember(tenant, "fv-rule-member@example.org", "MEMBER");

    String created =
        mockMvc
            .perform(
                post("/api/v1/items")
                    .session(owner)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "name", "A lathe",
                                "kind", "DIGITAL",
                                "itemTypeId", typeId.toString(),
                                "attributes", "{\"purchasePrice\":\"999.00\"}"))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID itemId = UUID.fromString(json.readTree(created).get("id").asString());

    MockHttpSession member = login("fv-rule-member@example.org");
    mockMvc
        .perform(get("/api/v1/items/" + itemId).session(member))
        .andExpect(
            jsonPath("$.attributes")
                .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("999.00"))));

    mockMvc
        .perform(
            post("/api/v1/field-visibility")
                .session(owner)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("fieldKey", "purchasePrice", "role", "MEMBER"))))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(get("/api/v1/field-visibility").session(owner))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.purchasePrice[0].role").value("MEMBER"));

    // The same session, without signing in again: a rule is read per request.
    mockMvc
        .perform(get("/api/v1/items/" + itemId).session(member))
        .andExpect(jsonPath("$.attributes").value(org.hamcrest.Matchers.containsString("999.00")));

    mockMvc
        .perform(
            delete("/api/v1/field-visibility/purchasePrice")
                .param("role", "MEMBER")
                .session(owner)
                .with(csrf()))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(get("/api/v1/items/" + itemId).session(member))
        .andExpect(
            jsonPath("$.attributes")
                .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("999.00"))));
  }

  // -------------------------------------------------------------------------

  /** A provisioned tenant and the user who owns it. */
  private record Tenant(UUID tenantId, UUID ownerId) {}

  /**
   * An item type carrying one sensitive field and one ordinary one.
   *
   * @param tenant whose type system
   * @return the published type's id
   */
  private UUID aTypeWithAPurchasePrice(Tenant tenant) {
    return TenantContext.callAs(
        tenant.tenantId(),
        () -> {
          var type =
              types.createItemType(
                  new TypeAdministration.CreateItemTypeCommand(
                      "tool-" + UUID.randomUUID().toString().substring(0, 8),
                      de.greluc.homeinv.catalog.api.TypeKind.PHYSICAL,
                      null,
                      null),
                  tenant.ownerId());
          types.addField(
              type.draftVersionId(),
              field("purchasePrice", FieldDataType.DECIMAL, true),
              tenant.ownerId());
          types.addField(
              type.draftVersionId(), field("colour", FieldDataType.TEXT, false), tenant.ownerId());
          types.publish(type.draftVersionId(), tenant.ownerId());
          return type.id();
        });
  }

  /**
   * One field definition, sensitive or not.
   *
   * @param key the field's key
   * @param type its data type
   * @param sensitive whether reading it needs a rule
   * @return the command
   */
  private static TypeAdministration.FieldCommand field(
      String key, FieldDataType type, boolean sensitive) {
    return new TypeAdministration.FieldCommand(
        key,
        type,
        Map.of("en", key),
        Map.of(),
        false,
        null,
        de.greluc.homeinv.catalog.api.FieldConstraints.NONE,
        null,
        null,
        null,
        0,
        false,
        false,
        false,
        sensitive);
  }

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
    return userId;
  }

  private MockHttpSession login(String email) throws Exception {
    MockHttpSession session = new MockHttpSession();
    mockMvc
        .perform(
            post("/api/v1/auth/login")
                .session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
        .andExpect(status().isOk());
    return session;
  }

  private static RequestPostProcessor csrf() {
    return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
        .csrf();
  }
}
