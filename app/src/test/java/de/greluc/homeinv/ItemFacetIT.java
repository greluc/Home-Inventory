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

import de.greluc.homeinv.catalog.api.FieldConstraints;
import de.greluc.homeinv.catalog.api.FieldDataType;
import de.greluc.homeinv.catalog.api.TypeAdministration;
import de.greluc.homeinv.catalog.api.TypeKind;
import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
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
 * Counting a list by the dimensions beside it (REQ-SRCH-002).
 *
 * <p>Facets are the sidebar: how many rows the list would still hold for each type, tag, place and
 * facetable field. They are a fourth member of the envelope beside {@code data}, {@code page} and
 * {@code meta}, and absent altogether from a listing that did not ask for them.
 *
 * <p>Two properties are worth more than "the counts are right", and both are decisions taken with
 * the owner on 2026-09-14. A facet is counted <b>without its own filter</b>, so it keeps showing
 * where somebody could click next instead of echoing what they already clicked; every other
 * dimension's filters do narrow it. And the location facet is the exception that keeps its filter,
 * because a tree is drilled into by descending: inside the shed one wants the shed's shelves.
 */
@DisplayName("A counted list")
class ItemFacetIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple-42";
  private static final String ITEMS = "/api/v1/items";

  @Autowired private TypeAdministration types;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private AppUserRepository users;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private ObjectMapper json;

  @Test
  @DisplayName("is absent unless somebody asked for it")
  void absentByDefault() throws Exception {
    Fixture fixture = aShed("absent");

    JsonNode body = list(fixture);
    assertThat(body.has("data")).isTrue();
    // Null is omitted by the serialiser, so "nobody asked" is an absent member
    // rather than an empty list, which would mean "counted, and there is nothing".
    assertThat(body.has("facets")).isFalse();

    assertThat(list(fixture, "facet", "tag").get("facets")).isNotNull();
  }

  @Test
  @DisplayName("counts types, categories, tags, places and facetable fields")
  void countsEveryDimension() throws Exception {
    Fixture fixture = aShed("dimensions");

    // Two tools and one book, so the type facet is 2 and 1.
    assertThat(bucketsOf(fixture, "type")).containsExactly(Map.entry("tool", 2L), Map.entry("book", 1L));

    // A category is the type above the type: both tools sit under "equipment",
    // the book sits under nothing and therefore in no bucket.
    assertThat(bucketsOf(fixture, "category")).containsExactly(Map.entry("equipment", 2L));

    assertThat(bucketsOf(fixture, "tag"))
        .containsExactly(Map.entry("heavy", 2L), Map.entry("borrowed", 1L), Map.entry("broken", 1L));

    // The places are counted per root of the tree, each carrying its whole
    // subtree: the shed holds the hammer on its shelf as well as the drill.
    assertThat(bucketsOf(fixture, "location"))
        .containsExactly(
            Map.entry(fixture.shed().toString(), 2L), Map.entry(fixture.study().toString(), 1L));

    assertThat(bucketsOf(fixture, "attr.manufacturer"))
        .containsExactly(Map.entry("Bosch", 1L), Map.entry("Gedore", 1L));
  }

  @Test
  @DisplayName("counts a dimension without its own filter, and with every other one")
  void drillDown() throws Exception {
    Fixture fixture = aShed("drill-down");

    // Filtered to one tag, the tag facet still shows all three: that is what
    // makes it a control rather than an echo of the click that produced it.
    assertThat(bucketsOf(fixture, "tag", "tag:broken"))
        .containsExactly(Map.entry("heavy", 2L), Map.entry("borrowed", 1L), Map.entry("broken", 1L));

    // Another dimension's filter does narrow it. Only the hammer is tagged
    // "broken", so under that filter the type facet is the hammer's type alone.
    assertThat(bucketsOf(fixture, "type", "tag:broken")).containsExactly(Map.entry("tool", 1L));

    // And both rules at once: the tag facet under a type filter counts the tags
    // of the two tools, not of everything.
    assertThat(bucketsOf(fixture, "tag", "type:tool"))
        .containsExactly(Map.entry("heavy", 2L), Map.entry("broken", 1L));
  }

  @Test
  @DisplayName("descends the location tree rather than showing its siblings")
  void locationsDescend() throws Exception {
    Fixture fixture = aShed("tree");

    // Inside the shed, the facet counts the shed's children — the shelf with the
    // hammer on it. The drill lies in the shed itself and is in none of the
    // shed's children, so it is in no bucket: the counts must not add up to more
    // than the list.
    assertThat(bucketsOf(fixture, "location", "location:subtree:" + fixture.shed()))
        .containsExactly(Map.entry(fixture.shelf().toString(), 1L));

    // The study has nothing under it, so inside it there is nothing to descend to.
    assertThat(bucketsOf(fixture, "location", "location:subtree:" + fixture.study())).isEmpty();
  }

  @Test
  @DisplayName("still counts when the filters together match nothing")
  void anEmptyListStillHasASidebar() throws Exception {
    Fixture fixture = aShed("empty");

    JsonNode body = list(fixture, "facet", "tag", "filter", "tag:broken", "filter", "tag:borrowed");
    assertThat(body.get("data")).isEmpty();
    // Nothing carries both tags, so the list is empty — and the sidebar is how
    // somebody gets back out of that, so it is still counted.
    assertThat(buckets(body, "tag")).isNotEmpty();
  }

  @Test
  @DisplayName("refuses a dimension it cannot count rather than leaving it out")
  void whatIsRefused() throws Exception {
    Fixture fixture = aShed("refusals");

    // A sidebar silently missing a section looks like a sidebar with nothing in
    // it, so an unknown dimension is named rather than dropped.
    refused(fixture, "colour");
    refused(fixture, "attr.nothingLikeThis");

    // Declared, stored, searchable — and not facetable. The tenant said so.
    refused(fixture, "attr.serial");
  }

  // -------------------------------------------------------------------------

  /**
   * The buckets of one dimension, as value to count.
   *
   * @param fixture whose list
   * @param dimension what to count
   * @param filters any filters to apply first, as {@code filter} parameters
   * @return the buckets, in the order the response gave them
   * @throws Exception when the request fails, which is the test failing
   */
  private Map<String, Long> bucketsOf(Fixture fixture, String dimension, String... filters)
      throws Exception {
    String[] params = new String[2 + filters.length * 2];
    params[0] = "facet";
    params[1] = dimension;
    for (int i = 0; i < filters.length; i++) {
      params[2 + i * 2] = "filter";
      params[3 + i * 2] = filters[i];
    }
    return buckets(list(fixture, params), dimension);
  }

  private Map<String, Long> buckets(JsonNode body, String dimension) {
    Map<String, Long> counts = new LinkedHashMap<>();
    for (JsonNode facet : body.get("facets")) {
      if (dimension.equals(facet.get("dimension").asString())) {
        facet.get("buckets")
            .forEach(bucket -> counts.put(bucket.get("value").asString(), bucket.get("count").asLong()));
        return counts;
      }
    }
    throw new IllegalStateException("No facet for " + dimension + " in " + body);
  }

  private JsonNode list(Fixture fixture, String... params) throws Exception {
    MockHttpServletRequestBuilder request = get(ITEMS).param("limit", "50").session(fixture.session());
    for (int i = 0; i < params.length; i += 2) {
      request = request.param(params[i], params[i + 1]);
    }
    String body =
        mockMvc
            .perform(request)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    return json.readTree(body);
  }

  private void refused(Fixture fixture, String dimension) throws Exception {
    mockMvc
        .perform(get(ITEMS).param("facet", dimension).session(fixture.session()))
        .andExpect(status().isUnprocessableContent());
  }

  // -------------------------------------------------------------------------

  /**
   * A tenant with two tools and a book, arranged so that no two dimensions split them alike.
   *
   * @param name distinguishes this test's tenant from the others'
   * @return the fixture
   * @throws Exception when provisioning, signing in or any setup call fails
   */
  private Fixture aShed(String name) throws Exception {
    String email = "facet-" + name + "@example.org";
    UUID userId = UUID.randomUUID();
    transactions.executeWithoutResult(
        status ->
            users.save(
                AppUser.create(
                    userId, email, "Counter", "en", passwordEncoder.encode(PASSWORD), Instant.now())));
    enrolSecondFactor(userId);
    UUID tenantId = provisioning.provision("Counting " + name, userId);
    TenantContext.runAs(
        tenantId,
        () -> {
          UUID equipment = aType("equipment", null, userId);
          aType("tool", equipment, userId);
          aType("book", null, userId);
        });

    MockHttpSession session = signIn(email, PASSWORD);
    UUID shed = aLocation(session, "Shed", null);
    UUID shelf = aLocation(session, "Shelf", shed);
    UUID study = aLocation(session, "Study", null);

    UUID hammer = anItem(session, "Hammer", "tool", shelf, "{\"manufacturer\":\"Bosch\",\"serial\":\"H-1\"}");
    UUID drill = anItem(session, "Drill", "tool", shed, "{\"manufacturer\":\"Gedore\",\"serial\":\"D-2\"}");
    UUID book = anItem(session, "Refactoring", "book", study, null);

    tag(session, hammer, aTag(session, "broken"));
    UUID heavy = aTag(session, "heavy");
    tag(session, hammer, heavy);
    tag(session, drill, heavy);
    tag(session, book, aTag(session, "borrowed"));

    return new Fixture(session, shed, shelf, study);
  }

  /**
   * A published item type, optionally under another one so that a category facet has something to
   * count.
   *
   * @param key the type key
   * @param parentId the type above it, or {@code null} for one at the root
   * @param userId who is editing the type system
   * @return the type's id
   */
  private UUID aType(String key, UUID parentId, UUID userId) {
    TypeAdministration.ItemTypeView type =
        types.createItemType(
            new TypeAdministration.CreateItemTypeCommand(key, TypeKind.PHYSICAL, parentId, null),
            userId);
    if ("tool".equals(key)) {
      types.addField(type.draftVersionId(), field("manufacturer", true), userId);
      // Searchable and NOT facetable, so a refusal has something real to refuse.
      types.addField(type.draftVersionId(), field("serial", false), userId);
    }
    types.publish(type.draftVersionId(), userId);
    return type.id();
  }

  /**
   * A text field that is always searchable and facetable only when asked.
   *
   * @param key the attribute key
   * @param facetable whether it may be counted
   * @return the command
   */
  private TypeAdministration.FieldCommand field(String key, boolean facetable) {
    return new TypeAdministration.FieldCommand(
        key,
        FieldDataType.TEXT,
        Map.of("en", key),
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
        facetable,
        false);
  }

  private UUID anItem(
      MockHttpSession session, String name, String typeKey, UUID locationId, String attributes)
      throws Exception {
    Map<String, String> body = new LinkedHashMap<>();
    body.put("name", name);
    body.put("kind", "PHYSICAL");
    body.put("itemTypeId", typeIdOf(session, typeKey).toString());
    body.put("locationId", locationId.toString());
    if (attributes != null) {
      body.put("attributes", attributes);
    }
    String created =
        mockMvc
            .perform(
                post(ITEMS)
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

    Map<String, String> body = new LinkedHashMap<>();
    body.put("name", name);
    body.put("categoryId", categoryId);
    if (parentId != null) {
      body.put("parentId", parentId.toString());
    }
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
   * The tenant this test counts lists in.
   *
   * @param session the authenticated caller
   * @param shed the root location holding the drill and, one level down, the hammer
   * @param shelf the location inside the shed
   * @param study the other root location, holding the book
   */
  private record Fixture(MockHttpSession session, UUID shed, UUID shelf, UUID study) {}
}
