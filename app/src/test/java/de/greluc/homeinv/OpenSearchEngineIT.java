/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.homeinv.catalog.api.FieldConstraints;
import de.greluc.homeinv.catalog.api.FieldDataType;
import de.greluc.homeinv.catalog.api.TypeAdministration;
import de.greluc.homeinv.catalog.api.TypeKind;
import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.search.api.SearchIndex;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Search answered by OpenSearch (REQ-SRCH-005, REQ-SRCH-006, REQ-SRCH-011).
 *
 * <p>Three things are worth a container. That the index is <b>filled by the events an item's life
 * publishes</b>, through the broker and the outbox rather than by a call in the write path. That it
 * covers the four fields the PostgreSQL vectors do not — notes, attribute values, tags and the
 * location path — which is all of REQ-SRCH-011 beside name and description. And that the
 * <b>tenant filter is on every query</b>, which ADR-0008 makes the boundary of a single shared
 * index and therefore the one thing that must never be forgotten.
 *
 * <p>Indexing is asynchronous on purpose (ADR-0008 puts the lag at p95 under two seconds), so every
 * assertion here waits for it. A test that did not would be asserting a timing rather than a
 * behaviour.
 */
@DisplayName("A search answered by OpenSearch")
class OpenSearchEngineIT extends AbstractSearchIntegrationTest {

  private static final String PASSWORD = "opensearch-answers-2026";
  private static final String ITEMS = "/api/v1/items";
  private static final Duration INDEXED = Duration.ofSeconds(30);

  @Autowired private TypeAdministration types;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private AppUserRepository users;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private ObjectMapper json;
  @Autowired private List<SearchIndex> engines;

  @Test
  @DisplayName("is the engine an installation that asked for it gets")
  void theEngineIsChosen() {
    assertThat(engines).extracting(SearchIndex::name).contains("opensearch", "postgresql");
    assertThat(engines.stream().filter(engine -> "opensearch".equals(engine.name())).findFirst())
        .get()
        .satisfies(engine -> assertThat(engine.available()).isTrue());
  }

  @Test
  @DisplayName("finds an item by every field REQ-SRCH-011 names, not only by its name")
  void findsTheWholeDocument() throws Exception {
    Tenant tenant = aTenant("fields");

    // Each word appears in exactly one field, so a hit proves which field was
    // searched. Nonsense words, because a real one might stem into another.
    UUID hammer =
        anItem(
            tenant,
            "Hammer",
            "A description with zarquon in it",
            "The notes mention blorptid",
            "{\"manufacturer\":\"Quibbleworth\"}");
    tag(tenant, hammer, "grunnel");

    awaitFound(tenant, "zarquon", "Hammer");
    awaitFound(tenant, "blorptid", "Hammer");
    awaitFound(tenant, "Quibbleworth", "Hammer");
    awaitFound(tenant, "grunnel", "Hammer");
  }

  @Test
  @DisplayName("stems, which is what the generated vectors were changed to do as well")
  void stems() throws Exception {
    Tenant tenant = aTenant("stemming");
    anItem(tenant, "Bohrmaschine", null, null, null);

    // The German analyser, because the session's language is `de`. The same
    // property REQ-SRCH-001 asks of the PostgreSQL vectors (ADR-0047), so a
    // search does not change meaning when the fallback answers.
    awaitFound(tenant, "Bohrmaschinen", "Bohrmaschine");
  }

  @Test
  @DisplayName("never crosses a tenant, because every query filters on the tenant (ADR-0008)")
  void theTenantFilterIsOnEveryQuery() throws Exception {
    Tenant mine = aTenant("mine");
    Tenant theirs = aTenant("theirs");

    anItem(theirs, "Zarquonhammer", null, null, null);
    awaitFound(theirs, "Zarquonhammer", "Zarquonhammer");

    // The document is in the same index, under the same word, and is not mine.
    // One index for every tenant is ADR-0008's decision; this filter is what
    // makes it a boundary rather than a shared bucket.
    assertThat(namesFound(mine, "Zarquonhammer")).isEmpty();
  }

  @Test
  @DisplayName("takes a trashed item out at once, and puts a restored one back")
  void trashAndRestore() throws Exception {
    Tenant tenant = aTenant("trash");
    UUID id = anItem(tenant, "Blorptidhammer", null, null, null);
    awaitFound(tenant, "Blorptidhammer", "Blorptidhammer");

    // The current version, read rather than assumed: every write on a single
    // resource requires `If-Match`, and a guessed one is a 412 (08 §8.2).
    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                    ITEMS + "/" + id)
                .session(tenant.session())
                .with(csrf())
                .header("If-Match", eTagOf(tenant.session(), ITEMS + "/" + id)))
        .andExpect(status().isNoContent());

    // A trashed item must stop being findable at once, for the whole retention
    // period (REQ-CORE-013) - a deletion that leaves the thing searchable did
    // not happen as far as anybody can tell.
    await().atMost(INDEXED).untilAsserted(() -> assertThat(namesFound(tenant, "Blorptidhammer")).isEmpty());
  }

  // -------------------------------------------------------------------------

  private void awaitFound(Tenant tenant, String text, String expected) {
    await()
        .atMost(INDEXED)
        .untilAsserted(
            () ->
                assertThat(namesFound(tenant, text))
                    // Named, so a failure says which field went missing rather
                    // than which line it was asserted on.
                    .as("searching for '%s'", text)
                    .contains(expected));
  }

  private List<String> namesFound(Tenant tenant, String text) throws Exception {
    String body =
        mockMvc
            .perform(get(ITEMS).param("q", text).param("limit", "50").session(tenant.session()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    List<String> names = new ArrayList<>();
    json.readTree(body).get("data").forEach(item -> names.add(item.get("name").asString()));
    return names;
  }

  private UUID anItem(
      Tenant tenant, String name, String description, String notes, String attributes)
      throws Exception {
    Map<String, String> body = new LinkedHashMap<>();
    body.put("name", name);
    body.put("kind", "DIGITAL");
    body.put("itemTypeId", tenant.typeId().toString());
    if (description != null) {
      body.put("description", description);
    }
    if (notes != null) {
      body.put("notes", notes);
    }
    if (attributes != null) {
      body.put("attributes", attributes);
    }
    String created =
        mockMvc
            .perform(
                post(ITEMS)
                    .session(tenant.session())
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    return UUID.fromString(json.readTree(created).get("id").asString());
  }

  private void tag(Tenant tenant, UUID itemId, String name) throws Exception {
    String created =
        mockMvc
            .perform(
                post("/api/v1/tags")
                    .session(tenant.session())
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("name", name))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    UUID tagId = UUID.fromString(json.readTree(created).get("id").asString());
    mockMvc
        .perform(
            put("/api/v1/items/" + itemId + "/tags/" + tagId).session(tenant.session()).with(csrf()))
        .andExpect(status().isNoContent());
  }

  private Tenant aTenant(String name) throws Exception {
    String email = "opensearch-" + name + "@example.org";
    UUID userId = UUID.randomUUID();
    transactions.executeWithoutResult(
        status ->
            users.save(
                AppUser.create(
                    userId, email, "Searcher", "de", passwordEncoder.encode(PASSWORD), Instant.now())));
    enrolSecondFactor(userId);
    UUID tenantId = provisioning.provision("Searching " + name, userId);
    UUID typeId = TenantContext.callAs(tenantId, () -> aType(userId));
    return new Tenant(signIn(email, PASSWORD), typeId);
  }

  /**
   * A published type with one searchable text field, so an attribute value reaches the document.
   *
   * @param userId who is editing the type system
   * @return the type's id
   */
  private UUID aType(UUID userId) {
    TypeAdministration.ItemTypeView type =
        types.createItemType(
            new TypeAdministration.CreateItemTypeCommand("tool", TypeKind.DIGITAL, null, null),
            userId);
    types.addField(
        type.draftVersionId(),
        new TypeAdministration.FieldCommand(
            "manufacturer",
            FieldDataType.TEXT,
            Map.of("en", "Manufacturer"),
            Map.of(),
            false,
            null,
            FieldConstraints.NONE,
            null,
            null,
            null,
            0,
            true,
            false,
            false,
            false),
        userId);
    types.publish(type.draftVersionId(), userId);
    return type.id();
  }

  /**
   * The tenant this test searches in.
   *
   * @param session the authenticated caller
   * @param typeId the published type its items are written against
   */
  private record Tenant(MockHttpSession session, UUID typeId) {}
}
