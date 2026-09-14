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
 * Named queries that appear as smart lists (REQ-SRCH-008).
 *
 * <p>What is worth asserting is that a saved search is a <b>question</b> and not an answer: running
 * one returns the list that query produces <i>now</i>, so an item created after the search was saved
 * appears in it. A smart list that answered from a cached set would be a report.
 *
 * <p>And that the stored form is the wire form. A client shows a saved search by putting its
 * filters back into the query bar, so what comes out has to be what a person would have typed.
 */
@DisplayName("A saved search")
class SavedSearchIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "saved-and-replayed-2026";
  private static final String SEARCHES = "/api/v1/saved-searches";
  private static final String ITEMS = "/api/v1/items";

  @Autowired private TypeAdministration types;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private AppUserRepository users;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private ObjectMapper json;

  @Test
  @DisplayName("stores the query as the wire spells it, and gives it back unchanged")
  void storesTheWireForm() throws Exception {
    Tenant tenant = aTenant("wire-form");

    JsonNode saved =
        save(tenant, "Schwere Werkzeuge", "Hammer", List.of("attr.manufacturer:Stanley"), "-name");

    assertThat(saved.get("name").asString()).isEqualTo("Schwere Werkzeuge");
    assertThat(saved.get("q").isNull()).isFalse();
    assertThat(saved.get("q").asString()).isEqualTo("Hammer");
    // Verbatim: a client puts this straight back into the query bar.
    assertThat(saved.get("filters").get(0).asString()).isEqualTo("attr.manufacturer:Stanley");
    assertThat(saved.get("sort").asString()).isEqualTo("-name");

    // And the listing shows it.
    assertThat(namesOf(tenant)).containsExactly("Schwere Werkzeuge");
  }

  @Test
  @DisplayName("answers with the list that query produces now, not the one it produced then")
  void itIsAQuestionNotAnAnswer() throws Exception {
    Tenant tenant = aTenant("current");
    anItem(tenant, "Hammer");

    UUID id = UUID.fromString(save(tenant, "Alles", null, List.of(), "name").get("id").asString());
    assertThat(itemsOf(tenant, id)).containsExactly("Hammer");

    // Saved before this existed.
    anItem(tenant, "Bohrmaschine");
    assertThat(itemsOf(tenant, id)).containsExactly("Bohrmaschine", "Hammer");
  }

  @Test
  @DisplayName("narrows the way the same filter typed by hand would")
  void itNarrows() throws Exception {
    Tenant tenant = aTenant("narrowing");
    anItem(tenant, "Hammer");
    anItem(tenant, "Bohrmaschine");

    UUID id =
        UUID.fromString(save(tenant, "Nur Hammer", "Hammer", List.of(), "name").get("id").asString());
    assertThat(itemsOf(tenant, id)).containsExactly("Hammer");
  }

  @Test
  @DisplayName("refuses a second one under the same name, however it is capitalised")
  void namesAreUniquePerTenant() throws Exception {
    Tenant tenant = aTenant("names");
    save(tenant, "Reparieren", null, List.of(), null);

    mockMvc
        .perform(
            post(SEARCHES)
                .session(tenant.session())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", "reparieren"))))
        .andExpect(status().isConflict());
  }

  @Test
  @DisplayName("refuses a filter that could never be run, when it is saved")
  void aBrokenFilterIsRefusedEarly() throws Exception {
    Tenant tenant = aTenant("broken");

    // A list that failed whenever anybody opened it would be reported by
    // somebody who cannot fix it. The person who can is the one saving it.
    mockMvc
        .perform(
            post(SEARCHES)
                .session(tenant.session())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("name", "Kaputt", "filters", List.of("colour:red")))))
        .andExpect(status().isUnprocessableContent());
  }

  @Test
  @DisplayName("is changed and removed under If-Match, and the removal is recorded")
  void changedAndRemoved() throws Exception {
    Tenant tenant = aTenant("lifecycle");
    UUID id =
        UUID.fromString(save(tenant, "Reparieren", null, List.of(), null).get("id").asString());
    String path = SEARCHES + "/" + id;

    mockMvc
        .perform(
            put(path)
                .session(tenant.session())
                .with(csrf())
                .header("If-Match", eTagOf(tenant.session(), path))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", "Zu reparieren", "q", "kaputt"))))
        .andExpect(status().isOk());
    assertThat(namesOf(tenant)).containsExactly("Zu reparieren");

    // A stale version is refused rather than overwriting somebody's change.
    mockMvc
        .perform(
            delete(path).session(tenant.session()).with(csrf()).header("If-Match", "\"1\""))
        .andExpect(status().isPreconditionFailed());

    mockMvc
        .perform(
            delete(path)
                .session(tenant.session())
                .with(csrf())
                .header("If-Match", eTagOf(tenant.session(), path)))
        .andExpect(status().isNoContent());
    assertThat(namesOf(tenant)).isEmpty();

    // Gone from the list, and gone from the resource.
    mockMvc.perform(get(path).session(tenant.session())).andExpect(status().isNotFound());
  }

  // -------------------------------------------------------------------------

  private JsonNode save(
      Tenant tenant, String name, String q, List<String> filters, String sort) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("name", name);
    if (q != null) {
      body.put("q", q);
    }
    body.put("filters", filters);
    if (sort != null) {
      body.put("sort", sort);
    }
    String created =
        mockMvc
            .perform(
                post(SEARCHES)
                    .session(tenant.session())
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    return json.readTree(created);
  }

  private List<String> namesOf(Tenant tenant) throws Exception {
    String body =
        mockMvc
            .perform(get(SEARCHES).param("limit", "50").session(tenant.session()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    List<String> names = new ArrayList<>();
    json.readTree(body).get("data").forEach(row -> names.add(row.get("name").asString()));
    return names;
  }

  private List<String> itemsOf(Tenant tenant, UUID id) throws Exception {
    String body =
        mockMvc
            .perform(
                get(SEARCHES + "/" + id + "/items").param("limit", "50").session(tenant.session()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    List<String> names = new ArrayList<>();
    json.readTree(body).get("data").forEach(item -> names.add(item.get("name").asString()));
    return names;
  }

  private void anItem(Tenant tenant, String name) throws Exception {
    mockMvc
        .perform(
            post(ITEMS)
                .session(tenant.session())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "name", name,
                            "kind", "DIGITAL",
                            "itemTypeId", tenant.typeId().toString()))))
        .andExpect(status().isCreated());
  }

  private Tenant aTenant(String name) throws Exception {
    String email = "saved-search-" + name + "@example.org";
    UUID userId = UUID.randomUUID();
    transactions.executeWithoutResult(
        status ->
            users.save(
                AppUser.create(
                    userId, email, "Saver", "de", passwordEncoder.encode(PASSWORD), Instant.now())));
    enrolSecondFactor(userId);
    UUID tenantId = provisioning.provision("Saving " + name, userId);
    UUID typeId =
        TenantContext.callAs(
            tenantId,
            () -> {
              TypeAdministration.ItemTypeView type =
                  types.createItemType(
                      new TypeAdministration.CreateItemTypeCommand(
                          "tool", TypeKind.DIGITAL, null, null),
                      userId);
              types.publish(type.draftVersionId(), userId);
              return type.id();
            });
    return new Tenant(signIn(email, PASSWORD), typeId);
  }

  /**
   * The tenant this test saves searches in.
   *
   * @param session the authenticated caller
   * @param typeId the published type its items are written against
   */
  private record Tenant(MockHttpSession session, UUID typeId) {}
}
