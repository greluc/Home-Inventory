/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.homeinv.catalog.api.TypeAdministration;
import de.greluc.homeinv.catalog.api.TypeKind;
import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Narrowing a list by type, tag or place (REQ-SRCH-002).
 *
 * <p>The other three filter dimensions of 08 §8.2, and the interesting thing about them is that
 * none of the three belongs to {@code inventory}. A type key is {@code catalog}'s, a tag assignment
 * is {@code tagging}'s and the tree is {@code locations}'. {@code search} resolves each through
 * that block's published port and hands the engine nothing but ids, so a narrowed list never costs
 * a join across a schema boundary (ADR-0002).
 *
 * <p>What is therefore worth asserting is not "the SQL works" but that the resolution is right: an
 * {@code in} widens within one dimension while two filters narrow across them, a subtree reaches
 * the grandchildren, a name nothing is called returns nothing rather than everything, and a
 * merged-away tag still finds the items somebody tagged with it.
 */
@DisplayName("A list narrowed by type, tag or place")
class ItemScopeFilteringIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple-42";
  private static final String ITEMS = "/api/v1/items";

  @Autowired private TypeAdministration types;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private AppUserRepository users;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private ObjectMapper json;

  @Test
  @DisplayName("narrows by the type key a person writes, not by its id")
  void byType() throws Exception {
    Fixture fixture = aShed("type");

    assertThat(namesOf(fixture, "type:tool")).containsExactly("Drill", "Hammer");
    assertThat(namesOf(fixture, "type:book")).containsExactly("Refactoring");
    assertThat(namesOf(fixture, "type:in:tool,book"))
        .containsExactly("Drill", "Hammer", "Refactoring");

    // A key no type is called matches no item. Not a refusal: "show me the
    // pianos" in a tenant with no piano type is a question with a true answer.
    assertThat(namesOf(fixture, "type:piano")).isEmpty();
  }

  @Test
  @DisplayName("narrows by tag, widening within the dimension and narrowing across it")
  void byTag() throws Exception {
    Fixture fixture = aShed("tag");

    assertThat(namesOf(fixture, "tag:broken")).containsExactly("Hammer");
    assertThat(namesOf(fixture, "tag:heavy")).containsExactly("Drill", "Hammer");

    // `in` is an "or" inside one dimension: that is what a multi-select facet
    // does, and it has to widen rather than narrow.
    assertThat(namesOf(fixture, "tag:in:broken,borrowed"))
        .containsExactly("Hammer", "Refactoring");

    // Two separate filters are an "and", because every filter given must hold.
    assertThat(namesOf(fixture, "tag:heavy", "tag:broken")).containsExactly("Hammer");
    assertThat(namesOf(fixture, "tag:heavy", "tag:borrowed")).isEmpty();

    // Across dimensions, likewise.
    assertThat(namesOf(fixture, "tag:heavy", "type:tool")).containsExactly("Drill", "Hammer");
    assertThat(namesOf(fixture, "tag:heavy", "type:book")).isEmpty();

    assertThat(namesOf(fixture, "tag:nothing-is-called-this")).isEmpty();
  }

  @Test
  @DisplayName("finds a merged tag's items under the name they were tagged with")
  void aMergedTagStillFinds() throws Exception {
    Fixture fixture = aShed("merged");

    // "broken" becomes "heavy". The merge repoints the assignments and leaves
    // "broken" as a tombstone pointing at what it became (REQ-CORE-063), so the
    // hammer is now tagged "heavy" and nothing is tagged "broken" at all.
    mockMvc
        .perform(
            post("/api/v1/tags/" + fixture.broken() + "/merge-into/" + fixture.heavy())
                .session(fixture.session())
                .with(csrf()))
        .andExpect(status().isOk());

    // The filter follows that pointer, which is the same redirect a client
    // holding the old id gets. Somebody who saved "everything broken" as a
    // filter still finds the hammer, instead of an empty list that looks like an
    // answer — and it is the drill too now, because that is what the merge
    // means.
    assertThat(namesOf(fixture, "tag:broken")).containsExactly("Drill", "Hammer");
    assertThat(namesOf(fixture, "tag:heavy")).containsExactly("Drill", "Hammer");
  }

  @Test
  @DisplayName("narrows to a place, and to everything under it")
  void byPlace() throws Exception {
    Fixture fixture = aShed("place");

    // The exact shelf, and nothing below it.
    assertThat(namesOf(fixture, "location:" + fixture.shelf())).containsExactly("Hammer");

    // The whole shed, which is two levels up from the hammer.
    assertThat(namesOf(fixture, "location:subtree:" + fixture.shed()))
        .containsExactly("Drill", "Hammer");

    // And a subtree with nothing in it.
    assertThat(namesOf(fixture, "location:subtree:" + fixture.study()))
        .containsExactly("Refactoring");
  }

  @Test
  @DisplayName("refuses a dimension it does not have, and a place that is not an id")
  void whatIsRefused() throws Exception {
    Fixture fixture = aShed("refusals");

    // A dimension nobody defined. Named rather than ignored: a caller who asked
    // to narrow by colour and silently got everything has a wrong list.
    refused(fixture, "colour:red");
    refused(fixture, "attribute.manufacturer:Stanley");

    // A location is named by id, and a key that is not one is a mistake worth
    // saying out loud rather than a subtree of nothing.
    refused(fixture, "location:the-shed");
    refused(fixture, "location:subtree:not-a-uuid");

    // The ordering operators are for attributes: a type is a set, not a scale.
    refused(fixture, "type:gte:tool");
    refused(fixture, "tag:lt:heavy");

    // And a subtree is only a thing a location has.
    refused(fixture, "type:subtree:tool");
  }

  @Test
  @DisplayName("binds a cursor to the filters it was taken from (REQ-SRCH-009)")
  void aCursorBelongsToItsScope() throws Exception {
    Fixture fixture = aShed("cursor");

    JsonNode first = page(fixture, 1, null, "type:tool");
    String cursor = first.get("page").get("nextCursor").asString();
    assertThat(cursor).isNotBlank();

    mockMvc
        .perform(request(fixture, 1, cursor, "type:book"))
        .andExpect(status().isBadRequest());
    mockMvc.perform(request(fixture, 1, cursor, "type:tool")).andExpect(status().isOk());
  }

  // -------------------------------------------------------------------------

  /**
   * Every name a narrowed list gives, paging to the end.
   *
   * @param fixture whose list
   * @param filters the {@code filter} parameters, all of which must hold
   * @return the names served, ordered by name so the assertions read as sets
   * @throws Exception when a request fails, which is the test failing
   */
  private List<String> namesOf(Fixture fixture, String... filters) throws Exception {
    List<String> names = new ArrayList<>();
    String cursor = null;
    do {
      JsonNode body = page(fixture, 50, cursor, filters);
      body.get("data").forEach(item -> names.add(item.get("name").asString()));
      JsonNode next = body.get("page").get("nextCursor");
      cursor = next == null || next.isNull() ? null : next.asString();
    } while (cursor != null);
    return names;
  }

  private JsonNode page(Fixture fixture, int limit, String cursor, String... filters)
      throws Exception {
    String body =
        mockMvc
            .perform(request(fixture, limit, cursor, filters))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    return json.readTree(body);
  }

  private void refused(Fixture fixture, String filter) throws Exception {
    mockMvc
        .perform(get(ITEMS).param("filter", filter).session(fixture.session()))
        .andExpect(status().isUnprocessableContent());
  }

  private MockHttpServletRequestBuilder request(
      Fixture fixture, int limit, String cursor, String... filters) {
    MockHttpServletRequestBuilder request =
        get(ITEMS)
            .param("limit", String.valueOf(limit))
            .param("sort", "name")
            .session(fixture.session());
    for (String filter : filters) {
      request = request.param("filter", filter);
    }
    return cursor == null ? request : request.param("cursor", cursor);
  }

  // -------------------------------------------------------------------------

  /**
   * A tenant holding two types, three tags and a shed with a shelf in it.
   *
   * <p>Shaped so that no two dimensions separate the three items the same way: the hammer and the
   * drill share a type, the hammer and the drill share a tag the book does not, the hammer and the
   * book share nothing, and the hammer sits two levels deep so a subtree filter has something to
   * walk.
   *
   * @param name distinguishes this test's tenant from the others'
   * @return the fixture
   * @throws Exception when provisioning, signing in or any setup call fails
   */
  private Fixture aShed(String name) throws Exception {
    String email = "scope-" + name + "@example.org";
    UUID userId = UUID.randomUUID();
    transactions.executeWithoutResult(
        status ->
            users.save(
                AppUser.create(
                    userId, email, "Scoper", "en", passwordEncoder.encode(PASSWORD), Instant.now())));
    enrolSecondFactor(userId);
    UUID tenantId = provisioning.provision("Scoping " + name, userId);
    TenantContext.runAs(
        tenantId,
        () -> {
          aType("tool", userId);
          aType("book", userId);
        });

    MockHttpSession session = signIn(email, PASSWORD);
    UUID shed = aLocation(session, "Shed", null);
    UUID shelf = aLocation(session, "Shelf", shed);
    UUID study = aLocation(session, "Study", null);

    UUID hammer = anItem(session, "Hammer", "tool", shelf);
    UUID drill = anItem(session, "Drill", "tool", shed);
    UUID refactoring = anItem(session, "Refactoring", "book", study);

    UUID broken = aTag(session, "broken");
    UUID heavy = aTag(session, "heavy");
    UUID borrowed = aTag(session, "borrowed");
    tag(session, hammer, broken);
    tag(session, hammer, heavy);
    tag(session, drill, heavy);
    tag(session, refactoring, borrowed);

    return new Fixture(session, shed, shelf, study, broken, heavy);
  }

  /**
   * A published item type with no fields; only its key matters here.
   *
   * @param key the type key a filter names
   * @param userId who is editing the type system
   */
  private void aType(String key, UUID userId) {
    TypeAdministration.ItemTypeView type =
        types.createItemType(
            new TypeAdministration.CreateItemTypeCommand(key, TypeKind.PHYSICAL, null, null),
            userId);
    types.publish(type.draftVersionId(), userId);
  }

  /**
   * One physical item, of the named type and in the named place.
   *
   * @param session the caller
   * @param name the item's name
   * @param typeKey which type, resolved through the listing so the test never invents an id
   * @param locationId where it is
   * @return the item's id
   * @throws Exception when the creation is not accepted
   */
  private UUID anItem(MockHttpSession session, String name, String typeKey, UUID locationId)
      throws Exception {
    String created =
        mockMvc
            .perform(
                post(ITEMS)
                    .session(session)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "name", name,
                                "kind", "PHYSICAL",
                                "itemTypeId", typeIdOf(session, typeKey).toString(),
                                "locationId", locationId.toString()))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    return UUID.fromString(json.readTree(created).get("id").asString());
  }

  private UUID typeIdOf(MockHttpSession session, String key) throws Exception {
    String body =
        mockMvc
            .perform(get("/api/v1/catalog/item-types?limit=200").session(session))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    for (JsonNode type : json.readTree(body).get("data")) {
      if (key.equals(type.get("key").asString())) {
        return UUID.fromString(type.get("id").asString());
      }
    }
    throw new IllegalStateException("No type called " + key);
  }

  private UUID aLocation(MockHttpSession session, String name, UUID parentId) throws Exception {
    String categories =
        mockMvc
            .perform(get("/api/v1/locations/categories?limit=200").session(session))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    String categoryId = json.readTree(categories).get("data").get(0).get("id").asString();

    Map<String, String> body =
        parentId == null
            ? Map.of("name", name, "categoryId", categoryId)
            : Map.of(
                "name", name, "categoryId", categoryId, "parentId", parentId.toString());
    String created =
        mockMvc
            .perform(
                post("/api/v1/locations")
                    .session(session)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    return UUID.fromString(json.readTree(created).get("id").asString());
  }

  private UUID aTag(MockHttpSession session, String name) throws Exception {
    String created =
        mockMvc
            .perform(
                post("/api/v1/tags")
                    .session(session)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("name", name))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    return UUID.fromString(json.readTree(created).get("id").asString());
  }

  private void tag(MockHttpSession session, UUID itemId, UUID tagId) throws Exception {
    mockMvc
        .perform(put("/api/v1/items/" + itemId + "/tags/" + tagId).session(session).with(csrf()))
        .andExpect(status().isNoContent());
  }

  /**
   * The tenant this test narrows lists in.
   *
   * @param session the authenticated caller
   * @param shed the root location
   * @param shelf the location inside the shed
   * @param study the other root location
   * @param broken the tag on the hammer alone
   * @param heavy the tag on the hammer and the drill
   */
  private record Fixture(
      MockHttpSession session, UUID shed, UUID shelf, UUID study, UUID broken, UUID heavy) {}
}
