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
 * Full text without OpenSearch, over everything REQ-SRCH-011 names.
 *
 * <p>The half of that requirement the {@code minimal} profile has, and the half every installation
 * falls back to when its index is down (REQ-SRCH-006). Four sources joined by "or": the generated
 * vector over the item's name, description and notes; the mirrored attribute values in
 * {@code item_attr_index}; the tags, which {@code tagging} matches; and the location path, which
 * {@code locations} matches and descends.
 *
 * <p>The last two cannot be a wider generated column, and that is the whole reason this composition
 * exists: a generated column is a function of its own row, and a tag and a place are other blocks'
 * rows. `notes` could be and now is — it was left out when it arrived at stage 1, so an item whose
 * notes read "wobbly leg, glued 2024" was findable by neither word.
 *
 * <p>No container beyond the ordinary ones: this is the engine every profile has.
 */
@DisplayName("Full text without OpenSearch")
class WideFullTextIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple-42";
  private static final String ITEMS = "/api/v1/items";

  @Autowired private TypeAdministration types;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private AppUserRepository users;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private ObjectMapper json;

  @Test
  @DisplayName("finds an item by its name, its description and its notes")
  void theItemsOwnWords() throws Exception {
    Fixture fixture = aShed("own-words");

    assertThat(found(fixture, "Hammer")).containsExactly("Hammer");
    assertThat(found(fixture, "zarquon")).containsExactly("Hammer");
    // The one that was missing until V45: `notes` arrived at stage 1 and nothing
    // widened the generated vectors to take it in.
    assertThat(found(fixture, "blorptid")).containsExactly("Hammer");
  }

  @Test
  @DisplayName("finds an item by a value of one of its attributes")
  void byAnAttributeValue() throws Exception {
    Fixture fixture = aShed("attributes");

    assertThat(found(fixture, "Quibbleworth")).containsExactly("Hammer");
  }

  @Test
  @DisplayName("finds an item by one of its tags, which live in another block")
  void byATag() throws Exception {
    Fixture fixture = aShed("tags");

    assertThat(found(fixture, "grunnel")).containsExactly("Hammer");

    // And not by a tag somebody else's item carries.
    assertThat(found(fixture, "flimbert")).containsExactly("Drill");
  }

  @Test
  @DisplayName("finds an item by the place it is in, and by the place above that")
  void byTheLocationPath() throws Exception {
    Fixture fixture = aShed("places");

    // The shelf the hammer is on.
    assertThat(found(fixture, "Zarquonregal")).containsExactly("Hammer");

    // And the shed the shelf is in, which is what makes it a *path*: a person
    // looking for something in the shed does not know which shelf.
    assertThat(found(fixture, "Blorptidschuppen")).containsExactlyInAnyOrder("Hammer", "Drill");
  }

  @Test
  @DisplayName("stems the other three sources the way it stems the first")
  void stemsEverywhere() throws Exception {
    Fixture fixture = aShed("stemming");

    // German plurals, in the tag and in the place. The configuration is the
    // caller's language in every one of the four sources, so a word does not
    // stem differently depending on which field it was found in.
    assertThat(found(fixture, "Blorptidschuppens"))
        .containsExactlyInAnyOrder("Hammer", "Drill");
  }

  // -------------------------------------------------------------------------

  private List<String> found(Fixture fixture, String text) throws Exception {
    String body =
        mockMvc
            .perform(
                get(ITEMS)
                    .param("q", text)
                    .param("limit", "50")
                    .param("sort", "name")
                    .session(fixture.session()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    List<String> names = new ArrayList<>();
    json.readTree(body).get("data").forEach(item -> names.add(item.get("name").asString()));
    return names;
  }

  /**
   * A shed with a shelf in it, a hammer on the shelf and a drill on the floor.
   *
   * <p>Every searchable word is nonsense, because a real one might stem into another and this test
   * is about which field was searched rather than about German morphology.
   *
   * @param name distinguishes this test's tenant from the others'
   * @return the fixture
   * @throws Exception when any setup call fails
   */
  private Fixture aShed(String name) throws Exception {
    String email = "wide-text-" + name + "@example.org";
    UUID userId = UUID.randomUUID();
    transactions.executeWithoutResult(
        status ->
            users.save(
                AppUser.create(
                    userId, email, "Searcher", "de", passwordEncoder.encode(PASSWORD), Instant.now())));
    enrolSecondFactor(userId);
    UUID tenantId = provisioning.provision("Wide text " + name, userId);
    UUID typeId = TenantContext.callAs(tenantId, () -> aType(userId));

    MockHttpSession session = signIn(email, PASSWORD);
    UUID shed = aLocation(session, "Blorptidschuppen", null);
    UUID shelf = aLocation(session, "Zarquonregal", shed);

    UUID hammer =
        anItem(
            session,
            typeId,
            "Hammer",
            shelf,
            "Eine Beschreibung mit zarquon darin",
            "Die Notizen erwähnen blorptid",
            "{\"manufacturer\":\"Quibbleworth\"}");
    UUID drill = anItem(session, typeId, "Drill", shed, null, null, null);

    tag(session, hammer, "grunnel");
    tag(session, drill, "flimbert");

    return new Fixture(session);
  }

  private UUID aType(UUID userId) {
    TypeAdministration.ItemTypeView type =
        types.createItemType(
            new TypeAdministration.CreateItemTypeCommand("tool", TypeKind.PHYSICAL, null, null),
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

  private UUID anItem(
      MockHttpSession session,
      UUID typeId,
      String name,
      UUID locationId,
      String description,
      String notes,
      String attributes)
      throws Exception {
    Map<String, String> body = new LinkedHashMap<>();
    body.put("name", name);
    body.put("kind", "PHYSICAL");
    body.put("itemTypeId", typeId.toString());
    body.put("locationId", locationId.toString());
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

  private void tag(MockHttpSession session, UUID itemId, String name) throws Exception {
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
    UUID tagId = UUID.fromString(json.readTree(created).get("id").asString());
    mockMvc
        .perform(put("/api/v1/items/" + itemId + "/tags/" + tagId).session(session).with(csrf()))
        .andExpect(status().isNoContent());
  }

  /**
   * The tenant this test searches in.
   *
   * @param session the authenticated caller
   */
  private record Fixture(MockHttpSession session) {}
}
