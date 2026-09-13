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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
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
 * One change over many items (REQ-CORE-011).
 *
 * <p>Four things have to hold, and the first is the one everything else rests on: <b>an entry that
 * fails does not take the others with it</b>. That is not what a transaction does by default — every
 * operation here is itself {@code @Transactional}, so one that throws while participating in an
 * enclosing transaction marks it rollback-only and the commit loses the lot. Partial success needs a
 * transaction per entry (ADR-0063), and the test for it is that the successful entries are still
 * there afterwards.
 *
 * <p>The other three: the status code says whether anything failed ({@code 200} against {@code 207},
 * decided with the owner on 2026-09-13), each entry carries its own {@code Idempotency-Key}, and a
 * change of type keeps the values the new type can hold and drops the rest.
 *
 * <p>Driven over HTTP, because the status code is half the contract.
 */
@DisplayName("A bulk operation over items")
class BulkItemOperationsIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "five-hundred-at-a-time-2026";
  private static final String BULK = "/api/v1/items/bulk";

  @Autowired private TenantProvisioningService provisioning;
  @Autowired private AppUserRepository users;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private ObjectMapper json;

  @Test
  @DisplayName("applies the entries that can be applied and reports the one that cannot")
  void oneFailureLeavesTheRestApplied() throws Exception {
    MockHttpSession session = tenantSession("partial");
    UUID house = aLocation(session, "House");
    UUID cellar = aLocation(session, "Cellar");
    UUID first = aPhysicalItem(session, "Ladder", house);
    UUID second = aPhysicalItem(session, "Paint", house);
    UUID third = aPhysicalItem(session, "Brushes", house);
    UUID gone = UUID.randomUUID();

    // The third entry names nothing. Were the call one transaction, that exception
    // would mark it rollback-only and the moves either side of it would be lost at
    // the commit -- which is exactly what this asserts did not happen.
    String body =
        bulk(
            "MOVE",
            Map.of("locationId", cellar.toString()),
            entry(first),
            entry(second),
            entry(gone),
            entry(third));

    mockMvc
        .perform(
            post(BULK)
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isMultiStatus())
        .andExpect(jsonPath("$.entries.length()").value(4))
        .andExpect(jsonPath("$.entries[0].status").value(200))
        .andExpect(jsonPath("$.entries[1].status").value(200))
        .andExpect(jsonPath("$.entries[2].status").value(404))
        .andExpect(jsonPath("$.entries[2].itemId").value(gone.toString()))
        .andExpect(
            jsonPath("$.entries[2].type").value("https://home-inv.example/problems/not-found"))
        .andExpect(jsonPath("$.entries[3].status").value(200));

    // The three that worked are in the cellar, and they are still there after the
    // call that also failed. This is the whole point of a transaction per entry.
    for (UUID moved : List.of(first, second, third)) {
      mockMvc
          .perform(get("/api/v1/items/" + moved).session(session))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.locationId").value(cellar.toString()));
    }
  }

  @Test
  @DisplayName("answers 200 when every entry was applied, and 207 only when one was not")
  void theStatusCodeSaysWhetherAnythingFailed() throws Exception {
    MockHttpSession session = tenantSession("codes");
    UUID house = aLocation(session, "House");
    UUID shed = aLocation(session, "Shed");
    UUID spade = aPhysicalItem(session, "Spade", house);
    UUID rake = aPhysicalItem(session, "Rake", house);

    mockMvc
        .perform(
            post(BULK)
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    bulk("MOVE", Map.of("locationId", shed.toString()), entry(spade), entry(rake))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.entries[0].status").value(200))
        // Nothing failed, so nothing says what failed. A client reading these
        // lines must not have to distinguish "no problem" from "a problem with no
        // type".
        .andExpect(jsonPath("$.entries[0].type").doesNotExist())
        .andExpect(jsonPath("$.entries[1].status").value(200));

    // A version that has moved on is this entry's 412 and nobody else's.
    long stale = versionOf(session, spade);
    mockMvc
        .perform(
            post(BULK)
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    bulk(
                        "MOVE",
                        Map.of("locationId", house.toString()),
                        versionedEntry(spade, stale - 1),
                        entry(rake))))
        .andExpect(status().isMultiStatus())
        .andExpect(jsonPath("$.entries[0].status").value(412))
        .andExpect(
            jsonPath("$.entries[0].type")
                .value("https://home-inv.example/problems/precondition-failed"))
        .andExpect(jsonPath("$.entries[1].status").value(200));

    // The refused entry stayed where it was; the other one moved.
    mockMvc
        .perform(get("/api/v1/items/" + spade).session(session))
        .andExpect(jsonPath("$.locationId").value(shed.toString()));
    mockMvc
        .perform(get("/api/v1/items/" + rake).session(session))
        .andExpect(jsonPath("$.locationId").value(house.toString()));
  }

  @Test
  @DisplayName("spends a key per entry, so a retry finishes what the first attempt did not")
  void keysAreSpentPerEntry() throws Exception {
    MockHttpSession session = tenantSession("keys");
    UUID house = aLocation(session, "House");
    UUID attic = aLocation(session, "Attic");
    UUID trunk = aPhysicalItem(session, "Trunk", house);
    UUID gone = UUID.randomUUID();

    String keyOne = UUID.randomUUID().toString();
    String keyTwo = UUID.randomUUID().toString();
    String body =
        bulk(
            "MOVE",
            Map.of("locationId", attic.toString()),
            keyedEntry(trunk, keyOne),
            keyedEntry(gone, keyTwo));

    mockMvc
        .perform(
            post(BULK)
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isMultiStatus())
        .andExpect(jsonPath("$.entries[0].status").value(200))
        .andExpect(jsonPath("$.entries[1].status").value(404));

    // The item is created again under the id that was missing, and the very same
    // request is sent a second time -- which is what a client with a dropped
    // connection does. The first entry must do nothing (its key is spent) and the
    // second must now work (its key was rolled back with its own transaction, so it
    // was never spent at all).
    anItemWithId(session, gone, "Christmas decorations", house);
    mockMvc
        .perform(
            post(BULK)
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.entries[0].status").value(200))
        .andExpect(jsonPath("$.entries[1].status").value(200));

    mockMvc
        .perform(get("/api/v1/items/" + gone).session(session))
        .andExpect(jsonPath("$.locationId").value(attic.toString()));

    // And a key spent on a move is not accepted later as having deleted anything:
    // one key, one change.
    mockMvc
        .perform(
            post(BULK)
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(bulk("DELETE", Map.of(), keyedEntry(trunk, keyOne))))
        .andExpect(status().isMultiStatus())
        .andExpect(jsonPath("$.entries[0].status").value(409))
        .andExpect(
            jsonPath("$.entries[0].type")
                .value("https://home-inv.example/problems/idempotency-key-conflict"));
  }

  @Test
  @DisplayName("carries over the attributes the new type can hold and drops the others")
  void changingTypeKeepsWhatFits() throws Exception {
    MockHttpSession session = tenantSession("types");
    UUID tool = importedType(session, "tool");
    UUID appliance = importedType(session, "appliance");

    // `manufacturer` and `model` are text in both templates; `corded` is a boolean
    // the appliance template does not declare at all. So two survive and one does
    // not -- and the revision written by the change is where the third still is.
    UUID drill =
        aDigitalItemOfType(
            session,
            "Drill",
            tool,
            "{\"manufacturer\":\"Bosch\",\"model\":\"GSB 18\",\"corded\":false}");

    mockMvc
        .perform(
            post(BULK)
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    bulk("CHANGE_TYPE", Map.of("itemTypeId", appliance.toString()), entry(drill))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.entries[0].status").value(200));

    mockMvc
        .perform(get("/api/v1/items/" + drill).session(session))
        .andExpect(status().isOk())
        // `attributes` is JSON text in the view, not a nested object: the source of
        // truth is a jsonb column and the API hands it over as it stands.
        .andExpect(jsonPath("$.attributes").value(containsString("Bosch")))
        .andExpect(jsonPath("$.attributes").value(containsString("GSB 18")))
        .andExpect(jsonPath("$.attributes").value(not(containsString("corded"))));

    // Nothing was lost: the revision before the change still holds the value the
    // new type has nowhere to put.
    String revisions =
        mockMvc
            .perform(get("/api/v1/items/" + drill + "/revisions").session(session))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    assertThat(revisions).contains("corded");

    // Asking for the type it already has succeeds and changes nothing, so a
    // selection spanning three types can be given one type in a single pass.
    mockMvc
        .perform(
            post(BULK)
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    bulk("CHANGE_TYPE", Map.of("itemTypeId", appliance.toString()), entry(drill))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.entries[0].status").value(200));
  }

  @Test
  @DisplayName("tags and deletes a selection, and says nothing happened twice")
  void tagsAndDeletes() throws Exception {
    MockHttpSession session = tenantSession("tag-delete");
    UUID shelf = aLocation(session, "Shelf");
    UUID mug = aPhysicalItem(session, "Mug", shelf);
    UUID plate = aPhysicalItem(session, "Plate", shelf);
    UUID tag = aTag(session, "Kitchen");

    mockMvc
        .perform(
            post(BULK)
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(bulk("TAG", Map.of("tagId", tag.toString()), entry(mug), entry(plate))))
        .andExpect(status().isOk());

    mockMvc
        .perform(get("/api/v1/items/" + mug + "/tags").session(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].name").value("Kitchen"));

    // An item that is not there is this entry's 404 rather than a foreign-key
    // violation that would abort the transaction the other entries are using.
    mockMvc
        .perform(
            post(BULK)
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    bulk(
                        "TAG",
                        Map.of("tagId", tag.toString()),
                        entry(UUID.randomUUID()),
                        entry(mug))))
        .andExpect(status().isMultiStatus())
        .andExpect(jsonPath("$.entries[0].status").value(404))
        .andExpect(jsonPath("$.entries[1].status").value(200));

    mockMvc
        .perform(
            post(BULK)
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(bulk("DELETE", Map.of(), entry(mug), entry(plate))))
        .andExpect(status().isOk());

    mockMvc
        .perform(get("/api/v1/items/" + mug).session(session))
        .andExpect(status().isNotFound());

    // Deleting them again succeeds: a client retrying a call it never saw the
    // answer to must not be told it failed for succeeding twice.
    mockMvc
        .perform(
            post(BULK)
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(bulk("DELETE", Map.of(), entry(mug), entry(plate))))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("refuses a call no per-entry reporting could rescue, and changes nothing")
  void someFaultsBelongToTheCallAndNotToAnEntry() throws Exception {
    MockHttpSession session = tenantSession("refusals");
    UUID house = aLocation(session, "House");
    UUID shed = aLocation(session, "Shed");
    UUID barrow = aPhysicalItem(session, "Barrow", house);

    // An item named twice: each entry would get a line, and the second would
    // report on a state the first had already changed.
    mockMvc
        .perform(
            post(BULK)
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    bulk(
                        "MOVE",
                        Map.of("locationId", shed.toString()),
                        entry(barrow),
                        entry(barrow))))
        .andExpect(status().isUnprocessableContent());

    // A move with nowhere to move to.
    mockMvc
        .perform(
            post(BULK)
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(bulk("MOVE", Map.of(), entry(barrow))))
        .andExpect(status().isUnprocessableContent());

    // More than the 500 of 08 §8.2, refused by the bean validation before a
    // transaction is opened at all.
    List<Map<String, Object>> tooMany = new ArrayList<>();
    for (int index = 0; index <= 500; index++) {
      tooMany.add(entry(UUID.randomUUID()));
    }
    mockMvc
        .perform(
            post(BULK)
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    bulk(
                        "MOVE",
                        Map.of("locationId", shed.toString()),
                        tooMany.toArray(new Map[0]))))
        .andExpect(status().isUnprocessableContent());

    // None of the three touched anything.
    mockMvc
        .perform(get("/api/v1/items/" + barrow).session(session))
        .andExpect(jsonPath("$.locationId").value(house.toString()));
  }

  // -------------------------------------------------------------------------

  @SafeVarargs
  private String bulk(String operation, Map<String, String> target, Map<String, Object>... entries)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("operation", operation);
    body.putAll(target);
    body.put("entries", List.of(entries));
    return json.writeValueAsString(body);
  }

  private static Map<String, Object> entry(UUID itemId) {
    return Map.of("itemId", itemId.toString());
  }

  private static Map<String, Object> versionedEntry(UUID itemId, long version) {
    return Map.of("itemId", itemId.toString(), "version", version);
  }

  private static Map<String, Object> keyedEntry(UUID itemId, String key) {
    return Map.of("itemId", itemId.toString(), "idempotencyKey", key);
  }

  private long versionOf(MockHttpSession session, UUID itemId) throws Exception {
    String tag = eTagOf(session, "/api/v1/items/" + itemId);
    return Long.parseLong(tag.substring(1, tag.length() - 1));
  }

  private UUID aLocation(MockHttpSession session, String name) throws Exception {
    String categories =
        mockMvc
            .perform(get("/api/v1/locations/categories?limit=200").session(session))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    String categoryId = json.readTree(categories).get("items").get(0).get("id").asString();
    return created(
        session,
        "/api/v1/locations",
        Map.of("name", name, "categoryId", categoryId));
  }

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

  private UUID anItemWithId(MockHttpSession session, UUID id, String name, UUID locationId)
      throws Exception {
    return created(
        session,
        "/api/v1/items",
        Map.of(
            "id", id.toString(),
            "name", name,
            "kind", "PHYSICAL",
            "locationId", locationId.toString()));
  }

  private UUID aDigitalItemOfType(
      MockHttpSession session, String name, UUID itemTypeId, String attributes) throws Exception {
    return created(
        session,
        "/api/v1/items",
        Map.of(
            "name", name,
            "kind", "DIGITAL",
            "itemTypeId", itemTypeId.toString(),
            "attributes", attributes));
  }

  private UUID importedType(MockHttpSession session, String key) throws Exception {
    String created =
        mockMvc
            .perform(
                post("/api/v1/catalog/type-templates/" + key + "/import")
                    .session(session)
                    .with(csrf()))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    return UUID.fromString(json.readTree(created).get("id").asString());
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
    JsonNode node = json.readTree(response);
    return UUID.fromString(node.get("id").asString());
  }

  private MockHttpSession tenantSession(String name) throws Exception {
    String email = "bulk-" + name + "@example.org";
    UUID userId = UUID.randomUUID();
    transactions.executeWithoutResult(
        status ->
            users.save(
                AppUser.create(
                    userId,
                    email,
                    "Bulk operator",
                    "en",
                    passwordEncoder.encode(PASSWORD),
                    Instant.now())));
    enrolSecondFactor(userId);
    provisioning.provision("Bulk " + name, userId);
    return signIn(email, PASSWORD);
  }
}
