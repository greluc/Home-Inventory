/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Proves there is no blind overwrite (REQ-API-004).
 *
 * <p>Four statements, and the requirement is only true if all four hold: a read hands out an
 * {@code ETag}; a write without {@code If-Match} is refused with {@code 428}; a write with a tag
 * that is no longer current is refused with {@code 412} and says what the versions are; and a write
 * with the right tag goes through and hands back the next one.
 *
 * <p>Driven over HTTP on purpose. This is a property of the protocol surface — the header, the
 * status, the tag — and a test through the service would prove the comparison while proving nothing
 * about whether a client is ever asked for it.
 */
@DisplayName("A write on a single resource")
class EntityTagIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "if-match-or-do-not-2026";

  @Autowired private TenantProvisioningService provisioning;
  @Autowired private AppUserRepository users;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private ObjectMapper json;

  @Test
  @DisplayName("is refused without If-Match, refused with a stale one, and taken with the current")
  void theFullRoundTrip() throws Exception {
    MockHttpSession session = tenantSession("round-trip");
    UUID id = anItem(session, "Kettle");
    String path = "/api/v1/items/" + id;

    // A read hands out the tag. It is the version, quoted -- not a hash of the
    // body, which is redacted per caller and would differ between two people
    // looking at the same unchanged row.
    String first =
        mockMvc
            .perform(get(path).session(session))
            .andExpect(status().isOk())
            .andExpect(header().exists("ETag"))
            .andReturn()
            .getResponse()
            .getHeader("ETag");
    // Quoted and strong. What number it starts at is Hibernate's business, not
    // the contract's: what a client needs is that the tag identifies one state
    // and changes when the state does, both of which are asserted below.
    assertThat(first).isNotNull().startsWith("\"").endsWith("\"").doesNotStartWith("W/");

    // Without the header: 428, and the item is untouched.
    mockMvc
        .perform(
            put(path)
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("Kettle, renamed")))
        .andExpect(status().isPreconditionRequired())
        .andExpect(
            jsonPath("$.type")
                .value("https://home-inv.example/problems/precondition-required"));

    // `*` is refused too, and that is deliberate: RFC 9110 gives the wildcard
    // "the resource exists", which for an update means "overwrite whatever is
    // there" -- the blind overwrite the requirement forbids.
    mockMvc
        .perform(
            put(path)
                .session(session)
                .with(csrf())
                .header("If-Match", "*")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("Kettle, renamed")))
        .andExpect(status().isPreconditionRequired());

    // With the current tag: taken, and the answer carries the next one.
    MvcResult updated =
        mockMvc
            .perform(
                put(path)
                    .session(session)
                    .with(csrf())
                    .header("If-Match", first)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body("Kettle, renamed")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Kettle, renamed"))
            .andReturn();
    String second = updated.getResponse().getHeader("ETag");
    assertThat(second).isNotNull().isNotEqualTo(first);

    // The stale tag now: 412, naming both versions so a client can say what
    // happened rather than only that something did.
    mockMvc
        .perform(
            put(path)
                .session(session)
                .with(csrf())
                .header("If-Match", first)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("Kettle, renamed again")))
        .andExpect(status().isPreconditionFailed())
        .andExpect(
            jsonPath("$.type").value("https://home-inv.example/problems/precondition-failed"))
        .andExpect(jsonPath("$.expectedVersion").value(versionIn(first)))
        .andExpect(jsonPath("$.currentVersion").value(versionIn(second)));

    // The refusal changed nothing.
    mockMvc
        .perform(get(path).session(session))
        .andExpect(jsonPath("$.name").value("Kettle, renamed"));

    // A tag this API never issued is a 412 as well: it cannot match, and the
    // caller's next move is the same as for any mismatch.
    mockMvc
        .perform(
            put(path)
                .session(session)
                .with(csrf())
                .header("If-Match", "W/" + second)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("Kettle")))
        .andExpect(status().isPreconditionFailed());
  }

  @Test
  @DisplayName("covers deleting as well as changing, which is where a lost update is unrecoverable")
  void deletingNeedsTheTagToo() throws Exception {
    MockHttpSession session = tenantSession("deleting");
    UUID id = anItem(session, "Toaster");
    String path = "/api/v1/items/" + id;

    mockMvc
        .perform(delete(path).session(session).with(csrf()))
        .andExpect(status().isPreconditionRequired());

    String stale = eTagOf(session, path);

    // Change it, so the tag the caller holds goes stale, and then try to delete
    // with the old one: this is the case the rule is really for, because a
    // deletion taken on a stale view destroys somebody else's edit.
    String current =
        mockMvc
            .perform(
                put(path)
                    .session(session)
                    .with(csrf())
                    .header("If-Match", stale)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body("Toaster, four slice")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getHeader("ETag");

    mockMvc
        .perform(delete(path).session(session).with(csrf()).header("If-Match", stale))
        .andExpect(status().isPreconditionFailed());

    mockMvc
        .perform(delete(path).session(session).with(csrf()).header("If-Match", current))
        .andExpect(status().isNoContent());
  }

  @Test
  @DisplayName("covers a place, and a move is a write like any other")
  void locationsAreGuardedToo() throws Exception {
    MockHttpSession session = tenantSession("locations");
    UUID house = aLocation(session, "House", null);
    UUID cellar = aLocation(session, "Cellar", null);

    String path = "/api/v1/locations/" + cellar;
    String tag =
        mockMvc
            .perform(get(path).session(session))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getHeader("ETag");

    // A verb-path POST is a write on one resource, so it is guarded like a PUT.
    // The rule is about the resource, not about the method.
    mockMvc
        .perform(
            post(path + "/move")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"parentId\":\"" + house + "\"}"))
        .andExpect(status().isPreconditionRequired());

    mockMvc
        .perform(
            post(path + "/move")
                .session(session)
                .with(csrf())
                .header("If-Match", tag)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"parentId\":\"" + house + "\"}"))
        .andExpect(status().isOk())
        .andExpect(header().exists("ETag"));

    // And renaming with the tag that the move has just superseded is refused.
    mockMvc
        .perform(
            put(path)
                .session(session)
                .with(csrf())
                .header("If-Match", tag)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Basement\"}"))
        .andExpect(status().isPreconditionFailed());
  }

  // -------------------------------------------------------------------------

  /**
   * The number inside an entity tag.
   *
   * @param tag the tag, as a response carried it
   * @return the version it names
   */
  private static long versionIn(String tag) {
    return Long.parseLong(tag.substring(1, tag.length() - 1));
  }

  private String body(String name) {
    return "{\"name\":\"" + name + "\",\"quantity\":\"1\"}";
  }

  private UUID anItem(MockHttpSession session, String name) throws Exception {
    String created =
        mockMvc
            .perform(
                post("/api/v1/items")
                    .session(session)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"" + name + "\",\"kind\":\"DIGITAL\"}"))
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

    String parent = parentId == null ? "" : ",\"parentId\":\"" + parentId + "\"";
    String created =
        mockMvc
            .perform(
                post("/api/v1/locations")
                    .session(session)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"name\":\""
                            + name
                            + "\",\"categoryId\":\""
                            + categoryId
                            + "\""
                            + parent
                            + "}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    return UUID.fromString(json.readTree(created).get("id").asString());
  }

  private MockHttpSession tenantSession(String name) throws Exception {
    String email = "entity-tag-" + name + "@example.org";
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
    provisioning.provision("Entity tags " + name, userId);
    return signIn(email, PASSWORD);
  }
}
