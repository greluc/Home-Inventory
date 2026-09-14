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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves the chain end to end: provisioning, login, session, tenant context, row-level security.
 *
 * <p>Each test below fails for a different reason if a different link breaks, which is the point.
 * A single "it works" test that creates and reads an item would stay green if the tenant context
 * were never established at all — the reader would simply be the same session that wrote.
 */
@DisplayName("A tenant sees its own data and nothing else, through the whole stack")
class TenantIsolationIT extends AbstractIntegrationTest {

  @Autowired private TenantProvisioningService provisioning;
  @Autowired private AppUserRepository users;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private ObjectMapper json;

  private static final String PASSWORD = "correct horse battery staple";

  @Test
  @DisplayName("an item created by one tenant is invisible to another")
  void itemsDoNotCrossTenants() throws Exception {
    Fixture alice = provision("alice@example.org", "Alice's things");
    Fixture bob = provision("bob@example.org", "Bob's things");

    MockHttpSession aliceSession = login(alice.email());
    String itemId = createItem(aliceSession, "Bohrmaschine");

    // Alice sees her own item.
    mockMvc
        .perform(get("/api/v1/items/" + itemId).session(aliceSession))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Bohrmaschine"));

    // Bob does not. A 404 rather than a 403: telling him it exists but is not his
    // would confirm the id, which is what REQ-SEC-025 closes.
    MockHttpSession bobSession = login(bob.email());
    mockMvc
        .perform(get("/api/v1/items/" + itemId).session(bobSession))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("https://home-inv.example/problems/not-found"));
  }

  @Test
  @DisplayName("without a session there is no access at all")
  void unauthenticatedIsRejected() throws Exception {
    mockMvc.perform(get("/api/v1/items/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("a wrong password is answered exactly like an unknown address")
  void loginFailuresAreIndistinguishable() throws Exception {
    Fixture carol = provision("carol@example.org", "Carol's things");

    String wrongPassword = loginRaw(carol.email(), "not the password");
    String unknownAddress = loginRaw("nobody@example.org", PASSWORD);

    // Same status, same problem type, same detail. A difference in any of the
    // three would answer "does this address have an account here".
    assertThat(wrongPassword).isEqualTo(unknownAddress);
  }

  @Test
  @DisplayName("creating the same item twice with identical content is not an error")
  void identicalRecreationReturnsTheExistingItem() throws Exception {
    Fixture dave = provision("dave@example.org", "Dave's things");
    MockHttpSession session = login(dave.email());

    UUID chosenId = UUID.randomUUID();
    String body =
        """
        {"id":"%s","name":"Akkuschrauber","kind":"DIGITAL"}
        """
            .formatted(chosenId);

    mockMvc
        .perform(post("/api/v1/items").session(session).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated());

    // The retry a client makes when it never saw the first answer.
    mockMvc
        .perform(post("/api/v1/items").session(session).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(chosenId.toString()));
  }

  @Test
  @DisplayName("the same id with different content is a conflict")
  void differingRecreationConflicts() throws Exception {
    Fixture erin = provision("erin@example.org", "Erin's things");
    MockHttpSession session = login(erin.email());
    UUID chosenId = UUID.randomUUID();

    mockMvc
        .perform(post("/api/v1/items").session(session).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"id\":\"%s\",\"name\":\"Erstes\",\"kind\":\"DIGITAL\"}".formatted(chosenId)))
        .andExpect(status().isCreated());

    mockMvc
        .perform(post("/api/v1/items").session(session).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"id\":\"%s\",\"name\":\"Zweites\",\"kind\":\"DIGITAL\"}".formatted(chosenId)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value("https://home-inv.example/problems/resource-exists"));
  }

  @Test
  @DisplayName("a physical item without a location is refused with a field path")
  void physicalItemNeedsALocation() throws Exception {
    Fixture frank = provision("frank@example.org", "Frank's things");
    MockHttpSession session = login(frank.email());

    mockMvc
        .perform(post("/api/v1/items").session(session).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Hammer\",\"kind\":\"PHYSICAL\"}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.type").value("https://home-inv.example/problems/validation-failed"));
  }

  /**
   * Creates a user and a tenant they own, with the catalog seeded.
   *
   * @param email the login address
   * @param tenantName the tenant's display name
   * @return the fixture, carrying what the test needs to log in
   */
  private Fixture provision(String email, String tenantName) {
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
    UUID tenantId = provisioning.provision(tenantName, userId);
    return new Fixture(userId, tenantId, email);
  }

  /**
   * Logs in and returns the session to carry into later requests.
   *
   * @param email the address
   * @return the established session
   * @throws Exception when the request fails
   */
  private MockHttpSession login(String email) throws Exception {
    return signIn(email, PASSWORD);
  }

  /**
   * Attempts a login and returns the response body, for comparing failures.
   *
   * @param email the address
   * @param password the password
   * @return the body with volatile members removed, so two failures can be compared
   * @throws Exception when the request fails
   */
  private String loginRaw(String email, String password) throws Exception {
    String body =
        mockMvc
            .perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            java.util.Map.of("email", email, "password", password))))
            .andExpect(status().isUnauthorized())
            .andReturn()
            .getResponse()
            .getContentAsString();

    // traceId differs per request by design and says nothing about the account.
    JsonNode node = json.readTree(body);
    ((ObjectNode) node).remove("traceId");
    return node.toString();
  }

  /**
   * Creates an item and returns its id.
   *
   * @param session the caller's session
   * @param name the item name
   * @return the new item's id
   * @throws Exception when the request fails
   */
  private String createItem(MockHttpSession session, String name) throws Exception {
    String body =
        mockMvc
            .perform(
                post("/api/v1/items")
                    .session(session)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"%s\",\"kind\":\"DIGITAL\"}".formatted(name)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(body).get("id").asText();
  }

  /**
   * What a test needs about a provisioned tenant.
   *
   * @param userId the owner
   * @param tenantId the tenant
   * @param email the owner's login address
   */
  private record Fixture(UUID userId, UUID tenantId, String email) {}
}
