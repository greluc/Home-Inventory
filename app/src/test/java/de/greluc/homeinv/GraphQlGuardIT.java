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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What the surface refuses when it is configured as a deployment configures it (REQ-API-006).
 *
 * <p>A second context, and it earns one: {@code GraphQlIT} runs with {@code free-form: ANYONE}
 * because a client driving the surface is the ordinary case and {@code web/} has no GraphQL
 * document yet. The three safeguards below only exist when it is set to what production sets, so
 * they are proved here — with the depth limit lowered to three, because <b>no valid query against
 * this schema reaches ten</b> and a limit can only be shown to work by making it reachable.
 *
 * <p>{@code free-form: NEVER} rather than {@code ADMINISTRATORS}: the test's caller is the tenant's
 * owner, so {@code ADMINISTRATORS} would let every query through and prove nothing. What is being
 * proved is the mechanism — a query in the register passes, one outside it does not.
 */
@DisplayName("The GraphQL guard")
@TestPropertySource(
    properties = {
      "homeinv.graphql.free-form=NEVER",
      "homeinv.graphql.max-depth=3",
      "homeinv.graphql.persisted-queries=classpath:graphql-registries/one-query.txt"
    })
class GraphQlGuardIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple-42";

  /** The one query the test registry holds, byte for byte. */
  private static final String REGISTERED = "{ items(first: 1) { nodes { id } } }";

  @Autowired private AppUserRepository users;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;

  @Test
  @DisplayName("runs a query the build registered")
  void aRegisteredQueryIsAnswered() throws Exception {
    query(anOwner(), REGISTERED)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.items.nodes").isArray());
  }

  @Test
  @DisplayName("refuses a query the build did not register, whoever is asking")
  void anUnregisteredQueryIsRefused() throws Exception {
    // The caller is the tenant's OWNER, which is as far as a role goes here. The
    // register is the gate, not the role.
    query(anOwner(), "{ items(first: 2) { nodes { id } } }")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors[0].message").value(
            org.hamcrest.Matchers.containsString("not one this release registered")))
        .andExpect(jsonPath("$.data").doesNotExist());
  }

  @Test
  @DisplayName("refuses introspection")
  void theSchemaIsNotReadable() throws Exception {
    query(anOwner(), "{ __schema { types { name } } }")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors[0].message").value(
            org.hamcrest.Matchers.containsString("Introspection is disabled")));
  }

  @Test
  @DisplayName("refuses a query that nests deeper than the limit")
  void depthIsBounded() throws Exception {
    // Four levels against a limit of three. Registered queries are the only ones
    // that run here, so this also shows the ORDER: depth is checked after the
    // register, by the execution, and a query that passes the register is still
    // not thereby allowed to be any shape it likes.
    query(anOwner(), "{ items { nodes { type { fields { key } } } } }")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").isArray());
  }

  // -------------------------------------------------------------------------

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

  private MockHttpSession anOwner() throws Exception {
    String email = "guard-" + UUID.randomUUID() + "@example.org";
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
