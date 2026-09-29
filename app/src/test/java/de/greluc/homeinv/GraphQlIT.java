/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The read-only query surface, and the four limits that bound it (REQ-API-006, 08 §8.4).
 *
 * <p>Driven over HTTP with the ordinary session, because that is how a client reaches it: the
 * endpoint is behind the same filter chain, the same CSRF token and the same tenant context as
 * every REST endpoint, and a test that called the resolvers directly would prove none of that.
 *
 * <p>What is asserted here is the <b>shape of a refusal</b> as much as the shape of an answer. A
 * query over budget, too deep or repeated under fifty names must be refused <i>before</i> it runs,
 * and the only way to see "before" from outside is that the answer carries an error and no data.
 */
@DisplayName("The GraphQL surface")
class GraphQlIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple-42";

  @Autowired private AppUserRepository users;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;

  @Test
  @DisplayName("answers a query with the item, its type and where it is")
  void aQueryAnswersWithWhatItAskedFor() throws Exception {
    MockHttpSession session = anOwner();
    UUID itemId = createAnItem(session, "A drill");

    query(
            session,
            "{ item(id: \"" + itemId + "\") { id name kind lifecycleState"
                + " type { key kind builtin } attributes } }")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.item.name").value("A drill"))
        .andExpect(jsonPath("$.data.item.kind").value("DIGITAL"))
        .andExpect(jsonPath("$.data.item.type.key").exists())
        .andExpect(jsonPath("$.data.item.attributes").isMap());
  }

  @Test
  @DisplayName("pages items with a cursor and never an offset")
  void itemsArePaged() throws Exception {
    MockHttpSession session = anOwner();
    createAnItem(session, "One");
    createAnItem(session, "Two");

    query(session, "{ items(first: 1) { nodes { name } pageInfo { endCursor hasNextPage } } }")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.items.nodes.length()").value(1))
        .andExpect(jsonPath("$.data.items.pageInfo.hasNextPage").value(true))
        .andExpect(jsonPath("$.data.items.pageInfo.endCursor").isString());
  }

  @Test
  @DisplayName("has no mutation to send")
  void nothingIsWrittenThroughIt() throws Exception {
    MockHttpSession session = anOwner();

    query(session, "mutation { deleteItem(id: \"x\") { id } }")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").isArray())
        .andExpect(jsonPath("$.data").doesNotExist());
  }

  @Test
  @DisplayName("cannot be made to nest deeper than the schema allows")
  void theSchemaItselfIsShallow() throws Exception {
    MockHttpSession session = anOwner();

    query(
            session,
            "{ items { nodes { type { fields { key } } history { nodes { revision } } } } }")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist());
  }

  @Test
  @DisplayName("refuses a query that asks for one field too many times")
  void aliasesAreBounded() throws Exception {
    MockHttpSession session = anOwner();

    StringBuilder aliased = new StringBuilder("{");
    for (int copy = 0; copy < 25; copy++) {
      aliased.append(" a").append(copy).append(": items(first: 1) { nodes { id } }");
    }
    aliased.append(" }");

    query(session, aliased.toString())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").isArray())
        .andExpect(jsonPath("$.errors[0].message").value(
            org.hamcrest.Matchers.containsString("no field may appear more than")));
  }

  @Test
  @DisplayName("refuses a query whose declared cost exceeds the budget")
  void costIsBounded() throws Exception {
    MockHttpSession session = anOwner();

    query(
            session,
            "{ items(first: 200) { nodes { history(first: 200) { nodes { revision } }"
                + " photos(first: 200) { id } relations(first: 200) { id } } } }")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").isArray())
        .andExpect(jsonPath("$.data").doesNotExist());
  }

  /**
   * Sends one document, as a client does.
   *
   * <p>The body is built with Jackson rather than by string concatenation: a GraphQL document
   * carries quotes of its own, and escaping them by hand is how a test ends up asserting against a
   * parse error it introduced itself.
   *
   * @param session the caller
   * @param document the query, written plainly
   * @return the response, for assertions
   */
  private org.springframework.test.web.servlet.ResultActions query(
      MockHttpSession session, String document) throws Exception {
    String body =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .writeValueAsString(java.util.Map.of("query", document));
    org.springframework.test.web.servlet.ResultActions sent =
        mockMvc.perform(
            post("/graphql")
                .session(session)
                .with(SecurityMockMvcRequestPostProcessors.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));

    MvcResult result = sent.andReturn();
    return result.getRequest().isAsyncStarted()
        ? mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(
                result))
        : sent;
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
    return UUID.fromString(
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(result.getResponse().getContentAsString())
            .get("id")
            .asText());
  }

  private MockHttpSession anOwner() throws Exception {
    String email = "graphql-" + UUID.randomUUID() + "@example.org";
    UUID userId = UUID.randomUUID();
    transactions.executeWithoutResult(
        status ->
            users.save(
                AppUser.create(
                    userId, email, "Owner", "en", passwordEncoder.encode(PASSWORD),
                    Instant.now())));
    enrolSecondFactor(userId);
    provisioning.provision("Tenant of " + email, userId);
    return signIn(email, PASSWORD);
  }
}
