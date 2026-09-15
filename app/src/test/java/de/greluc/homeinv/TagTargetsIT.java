/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * A tag endpoint answers about the thing it was given, not about the assignment alone.
 *
 * <p>All six paths under {@code /items/{id}/tags} and {@code /locations/{id}/tags} have declared
 * {@code NOT_FOUND} since they were written, and none of them could produce it. {@code tagging} may
 * not read {@code inventory}'s or {@code locations}' schema (04 §4.5), so it had no way to tell a
 * missing target from an untagged one: putting a tag on an item that is not there reached the
 * database as a foreign-key violation and answered {@code 500}, while removing one and listing a
 * target's tags said nothing at all — which is worse, because it looks like an answer.
 *
 * <p>{@code TaggableTargets} is the port that closed it, and this is the test that says so. Driven
 * over HTTP because the status code is the whole claim.
 *
 * <p>The three cases that must be indistinguishable are all here: a target that never existed, one
 * in the trash, and one belonging to somebody else. A {@code 403} for the last would confirm it
 * exists, which is exactly what REQ-SEC-025 forbids.
 */
@DisplayName("A tag endpoint given a target that is not there")
class TagTargetsIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple-42";
  private static final String NOT_FOUND = "https://home-inv.example/problems/not-found";

  @Autowired private TenantProvisioningService provisioning;
  @Autowired private AppUserRepository users;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private ObjectMapper json;

  @Test
  @DisplayName("answers 404 on all three item paths, and never 500")
  void everyItemPathAnswersTheSameWay() throws Exception {
    MockHttpSession session = tenantSession("items");
    UUID tag = aTag(session, "Fragile");
    UUID missing = UUID.randomUUID();

    mockMvc
        .perform(put("/api/v1/items/" + missing + "/tags/" + tag).session(session).with(csrf()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value(NOT_FOUND));

    mockMvc
        .perform(delete("/api/v1/items/" + missing + "/tags/" + tag).session(session).with(csrf()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value(NOT_FOUND));

    mockMvc
        .perform(get("/api/v1/items/" + missing + "/tags").session(session))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value(NOT_FOUND));
  }

  @Test
  @DisplayName("answers 404 on all three place paths too")
  void everyPlacePathAnswersTheSameWay() throws Exception {
    MockHttpSession session = tenantSession("places");
    UUID tag = aTag(session, "Damp");
    UUID missing = UUID.randomUUID();

    mockMvc
        .perform(put("/api/v1/locations/" + missing + "/tags/" + tag).session(session).with(csrf()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value(NOT_FOUND));

    mockMvc
        .perform(
            delete("/api/v1/locations/" + missing + "/tags/" + tag).session(session).with(csrf()))
        .andExpect(status().isNotFound());

    mockMvc
        .perform(get("/api/v1/locations/" + missing + "/tags").session(session))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("answers 404 for another tenant's item, exactly as for one that never existed")
  void aForeignItemIsNotAForbiddenItem() throws Exception {
    MockHttpSession mine = tenantSession("mine");
    MockHttpSession theirs = tenantSession("theirs");

    UUID theirPlace = aLocation(theirs, "Their shed");
    UUID theirItem = aPhysicalItem(theirs, "Their mower", theirPlace);
    UUID myTag = aTag(mine, "Mine");

    // 404 and not 403: a 403 would confirm the item exists, which is the one fact
    // a caller from another tenant must not learn (REQ-SEC-025).
    mockMvc
        .perform(put("/api/v1/items/" + theirItem + "/tags/" + myTag).session(mine).with(csrf()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value(NOT_FOUND));

    mockMvc
        .perform(get("/api/v1/items/" + theirItem + "/tags").session(mine))
        .andExpect(status().isNotFound());

    mockMvc
        .perform(put("/api/v1/locations/" + theirPlace + "/tags/" + myTag).session(mine).with(csrf()))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("answers 404 for an item in the trash, so the tag paths agree with the item paths")
  void aTrashedItemIsGone() throws Exception {
    MockHttpSession session = tenantSession("trash");
    UUID shelf = aLocation(session, "Shelf");
    UUID mug = aPhysicalItem(session, "Mug", shelf);
    UUID tag = aTag(session, "Kitchen");

    mockMvc
        .perform(put("/api/v1/items/" + mug + "/tags/" + tag).session(session).with(csrf()))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(
            delete("/api/v1/items/" + mug)
                .session(session)
                .with(csrf())
                .header("If-Match", eTagOf(session, "/api/v1/items/" + mug)))
        .andExpect(status().isNoContent());

    // The item endpoint says it is gone, and so, now, does the tag endpoint. A
    // target one path accepts and another denies is a row somebody can tag and
    // then not find.
    mockMvc
        .perform(get("/api/v1/items/" + mug).session(session))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(put("/api/v1/items/" + mug + "/tags/" + tag).session(session).with(csrf()))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(get("/api/v1/items/" + mug + "/tags").session(session))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("leaves the working paths alone, assignment still being idempotent")
  void theCheckChangesNothingThatWorked() throws Exception {
    MockHttpSession session = tenantSession("working");
    UUID shelf = aLocation(session, "Shelf");
    UUID plate = aPhysicalItem(session, "Plate", shelf);
    UUID tag = aTag(session, "Crockery");
    String path = "/api/v1/items/" + plate + "/tags/" + tag;

    // Twice, and twice 204: a client retrying a request it never saw the answer to
    // must not be told it failed for succeeding.
    mockMvc.perform(put(path).session(session).with(csrf())).andExpect(status().isNoContent());
    mockMvc.perform(put(path).session(session).with(csrf())).andExpect(status().isNoContent());

    mockMvc
        .perform(get("/api/v1/items/" + plate + "/tags").session(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].name").value("Crockery"));

    // And taking it off twice is still fine. What the port refuses is a target
    // that is not there, which is a different question from an assignment that is
    // not there.
    mockMvc.perform(delete(path).session(session).with(csrf())).andExpect(status().isNoContent());
    mockMvc.perform(delete(path).session(session).with(csrf())).andExpect(status().isNoContent());

    mockMvc
        .perform(get("/api/v1/items/" + plate + "/tags").session(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(0));
  }

  // -------------------------------------------------------------------------

  private UUID aTag(MockHttpSession session, String name) throws Exception {
    return created(session, "/api/v1/tags", Map.of("name", name));
  }

  private UUID aPhysicalItem(MockHttpSession session, String name, UUID locationId)
      throws Exception {
    return created(
        session,
        "/api/v1/items",
        Map.of("name", name, "kind", "PHYSICAL", "locationId", locationId.toString()));
  }

  private UUID aLocation(MockHttpSession session, String name) throws Exception {
    String categories =
        mockMvc
            .perform(get("/api/v1/locations/categories?limit=200").session(session))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    String categoryId = json.readTree(categories).get("data").get(0).get("id").asString();
    return created(session, "/api/v1/locations", Map.of("name", name, "categoryId", categoryId));
  }

  private UUID created(MockHttpSession session, String path, Map<String, String> body)
      throws Exception {
    String response =
        mockMvc
            .perform(
                post(path)
                    .session(session)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    return UUID.fromString(json.readTree(response).get("id").asString());
  }

  private MockHttpSession tenantSession(String name) throws Exception {
    String email = "tag-targets-" + name + "@example.org";
    UUID userId = UUID.randomUUID();
    transactions.executeWithoutResult(
        status ->
            users.save(
                AppUser.create(
                    userId,
                    email,
                    "Tagger",
                    "en",
                    passwordEncoder.encode(PASSWORD),
                    Instant.now())));
    enrolSecondFactor(userId);
    provisioning.provision("Tag targets " + name, userId);
    return signIn(email, PASSWORD);
  }
}
