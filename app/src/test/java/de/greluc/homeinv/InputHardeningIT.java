/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.platform.TenantContext;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * What the API accepts from a client, and what it refuses (REQ-SEC-029, REQ-SEC-032, REQ-NFR-010).
 */
@DisplayName("Input")
class InputHardeningIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple-42";

  @Autowired private AppUserRepository users;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private ObjectMapper json;
  @Autowired private JdbcClient jdbc;

  @Test
  @DisplayName("an unknown field is refused, and the field is named (REQ-SEC-029)")
  void anUnknownFieldIsRefused() throws Exception {
    MockHttpSession session = sessionFor("unknown-field@example.org");

    mockMvc
        .perform(
            post("/api/v1/items")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                // `tenantId` is the case that matters: a client sending it and
                // having it silently dropped would believe it was honoured, and
                // would be wrong in the one direction that matters most.
                .content(
                    json.writeValueAsString(
                        Map.of("name", "A thing", "kind", "DIGITAL", "tenantId", UUID.randomUUID()))))
        // 422 and not 400: the body parsed perfectly. It said something this
        // endpoint does not accept, which is a validation failure.
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.errors[0].field").value("tenantId"))
        .andExpect(jsonPath("$.errors[0].message").value("unknown field"));
  }

  @Test
  @DisplayName("a decomposed name is stored composed, so two spellings are one thing (REQ-SEC-032)")
  void namesAreNormalisedToNfc() throws Exception {
    MockHttpSession session = sessionFor("normalising@example.org");

    // "Bohrmaschine" with a DECOMPOSED umlaut: o + U+0308 rather than U+00F6.
    // The two render identically and are different strings, so without
    // normalisation a search does not find them and two rows exist that a person
    // cannot tell apart.
    String decomposed = "L" + "o\u0308" + "tkolben";
    String composed = "L" + "\u00f6" + "tkolben";

    String body =
        mockMvc
            .perform(
                post("/api/v1/items")
                    .session(session)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("name", decomposed, "kind", "DIGITAL"))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

    org.assertj.core.api.Assertions.assertThat(json.readTree(body).get("name").asString())
        .isEqualTo(composed);
  }

  @Test
  @DisplayName("control characters are stripped, including the ones that reorder text")
  void controlCharactersAreStripped() throws Exception {
    MockHttpSession session = sessionFor("control-chars@example.org");

    // U+202E RIGHT-TO-LEFT OVERRIDE reverses everything after it in most
    // renderers. In a label, a log line or a file listing it makes text read as
    // something other than what it is, and nothing reading the source sees it.
    String hostile = "invoice" + "\u202e" + "gpj.exe";

    String body =
        mockMvc
            .perform(
                post("/api/v1/items")
                    .session(session)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("name", hostile, "kind", "DIGITAL"))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

    String stored = json.readTree(body).get("name").asString();
    org.assertj.core.api.Assertions.assertThat(stored).isEqualTo("invoicegpj.exe");
    org.assertj.core.api.Assertions.assertThat(stored).doesNotContain("\u202e");
  }

  @Test
  @DisplayName("a page larger than 200 is refused rather than quietly trimmed (REQ-NFR-010)")
  void thePageSizeIsCapped() throws Exception {
    MockHttpSession session = sessionFor("paging@example.org");

    // Refused, not silently reduced: a client asking for 5000 and getting 200
    // without being told will page wrongly and never notice.
    mockMvc
        .perform(
            get("/api/v1/search")
                .param("q", "")
                .param("language", "de")
                .param("limit", "5000")
                .session(session))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  @DisplayName("the items of a location are paged, subtree or not (REQ-CORE-049)")
  void locationItemsArePaged() throws Exception {
    MockHttpSession session = sessionFor("location-items@example.org");

    String location =
        mockMvc
            .perform(
                post("/api/v1/locations")
                    .session(session)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of("name", "Keller", "categoryId", builtInCategory.toString()))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

    UUID locationId = UUID.fromString(json.readTree(location).get("id").asString());

    mockMvc
        .perform(
            post("/api/v1/items")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "name", "Bohrmaschine",
                            "kind", "PHYSICAL",
                            "locationId", locationId.toString()))))
        .andExpect(status().isCreated());

    mockMvc
        .perform(get("/api/v1/locations/" + locationId + "/items").session(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].name").value("Bohrmaschine"));

    mockMvc
        .perform(
            get("/api/v1/locations/" + locationId + "/items")
                .param("includeSubtree", "true")
                .session(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1));

    // And the bound applies here too.
    mockMvc
        .perform(
            get("/api/v1/locations/" + locationId + "/items")
                .param("limit", "5000")
                .session(session))
        .andExpect(status().isUnprocessableEntity());
  }

  // -------------------------------------------------------------------------

  /**
   * The tenant's built-in location category.
   *
   * <p>Stage 0 has no category configuration (O27): provisioning creates the built-in set per
   * tenant. The id is read back rather than assumed, because a constant here would be a second
   * source for a fact provisioning already owns.
   */
  private UUID builtInCategory;

  private MockHttpSession sessionFor(String email) throws Exception {
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
    UUID tenantId = provisioning.provision("Tenant for " + email, userId);
    // Under the tenant context, because `catalog.location_category` is
    // tenant-scoped with FORCE row-level security: without it the query is not
    // refused, it simply returns nothing - which is the whole design working and
    // reads like a missing row.
    // Under the tenant context AND inside a transaction. The context is a
    // ThreadLocal; what the database sees is the `app.tenant_id` the transaction
    // manager pushes when the transaction begins. Without the transaction the
    // query runs on a connection that never had it set, and FORCE row-level
    // security answers with nothing - which is the design working and reads like
    // a missing row.
    builtInCategory =
        TenantContext.callAs(
            tenantId,
            () ->
                transactions.execute(
                    status ->
                        jdbc.sql(
                                "select id from catalog.location_category "
                                    + "where tenant_id = ? and key = 'room'")
                            .param(tenantId)
                            .query(UUID.class)
                            .single()));

    return signIn(email, PASSWORD);
  }
}
