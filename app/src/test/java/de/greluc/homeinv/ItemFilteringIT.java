/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * Narrowing a list by an attribute (REQ-SRCH-003).
 *
 * <p>Filters read {@code inventory.item_attr_index}, the side table {@code ItemAttributesIT} proves
 * is written in the same transaction as the JSONB. This test asks the other half of that question:
 * that a filter reaches the right column, compares in the right type, and refuses what it cannot
 * answer.
 *
 * <p>Three of these assertions are decisions taken with the owner on 2026-09-14 rather than
 * properties of the SQL. A range over a dimensioned field must name its unit and is refused without
 * one — {@code attr.purchasePrice:gte:100:EUR}, never {@code attr.purchasePrice:gte:100}, because
 * "over 100" across currencies is a number nobody asked for. A field no published type marks
 * searchable is named in the refusal rather than ignored. And a cursor does not survive a change of
 * filters, because the list it was taken from no longer exists.
 */
@DisplayName("A filtered list")
class ItemFilteringIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple-42";
  private static final String ITEMS = "/api/v1/items";

  @Autowired private TypeAdministration types;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private AppUserRepository users;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private ObjectMapper json;

  @Test
  @DisplayName("compares text in its own column, one value or several")
  void filteringOnText() throws Exception {
    Caller caller = aTenantWithTools("text");

    assertThat(namesOf(caller, "attr.manufacturer:Stanley")).containsExactly("Hammer");
    // The same filter with the operator spelled out.
    assertThat(namesOf(caller, "attr.manufacturer:eq:Stanley")).containsExactly("Hammer");
    assertThat(namesOf(caller, "attr.manufacturer:in:Stanley,Bosch"))
        .containsExactlyInAnyOrder("Hammer", "Drill");
    assertThat(namesOf(caller, "attr.manufacturer:Nobody")).isEmpty();
  }

  @Test
  @DisplayName("compares numbers as numbers, not as text")
  void filteringOnNumbers() throws Exception {
    Caller caller = aTenantWithTools("numbers");

    // The whole point of the cast. As text, '90' sorts after '100' and this
    // would answer "Drill" alone.
    assertThat(namesOf(caller, "attr.published:gte:100"))
        .containsExactlyInAnyOrder("Hammer", "Drill", "Spanner");
    assertThat(namesOf(caller, "attr.published:lt:2000")).containsExactly("Hammer");
  }

  @Test
  @DisplayName("compares a dimensioned value within its unit, and only there")
  void filteringWithinAUnit() throws Exception {
    Caller caller = aTenantWithTools("units");

    // The Spanner costs 150 USD and is not an answer to "at least 100 euros",
    // however the numbers compare. This is the assertion `unit_value` exists for.
    assertThat(namesOf(caller, "attr.purchasePrice:gte:100:EUR")).containsExactly("Drill");
    assertThat(namesOf(caller, "attr.purchasePrice:gte:100:USD")).containsExactly("Spanner");
    assertThat(namesOf(caller, "attr.purchasePrice:lte:50:EUR")).containsExactly("Hammer");

    // A quantity works the same way, with the unit the item was written with.
    assertThat(namesOf(caller, "attr.netWeight:gte:100:g"))
        .containsExactlyInAnyOrder("Hammer", "Drill");
    assertThat(namesOf(caller, "attr.netWeight:gte:100:kg")).isEmpty();
  }

  @Test
  @DisplayName("joins several filters with 'and'")
  void severalFiltersNarrow() throws Exception {
    Caller caller = aTenantWithTools("conjunction");

    assertThat(namesOf(caller, "attr.netWeight:gte:100:g", "attr.manufacturer:Bosch"))
        .containsExactly("Drill");
    assertThat(namesOf(caller, "attr.netWeight:gte:100:g", "attr.manufacturer:Nobody")).isEmpty();

    // Two filters over two different attributes of one item must not return it
    // twice: they are `exists` subqueries and not joins.
    assertThat(namesOf(caller, "attr.manufacturer:Bosch", "attr.published:gte:2000"))
        .containsExactly("Drill");
  }

  @Test
  @DisplayName("refuses a filter it cannot honour rather than ignoring it")
  void whatIsRefused() throws Exception {
    Caller caller = aTenantWithTools("refusals");

    // A range over money with no unit. 08 §8.2 showed exactly this and the
    // example was wrong; the answer would silently span every currency.
    refused(caller, "attr.purchasePrice:gte:100");
    refused(caller, "attr.netWeight:lt:500");

    // The converse: a unit on a field that has no dimension means nothing, so it
    // is a mistake worth naming rather than a part to drop.
    refused(caller, "attr.published:gte:2000:EUR");

    // A field no published type marks searchable, and one no type declares at all.
    refused(caller, "attr.serial:ABC-1");
    refused(caller, "attr.nothingLikeThis:1");

    // Not an attribute, no value, and an empty field.
    refused(caller, "name:Hammer");
    refused(caller, "attr.manufacturer");
    refused(caller, "attr.:Stanley");
  }

  @Test
  @DisplayName("refuses a cursor from a differently filtered list (REQ-SRCH-009)")
  void aCursorBelongsToItsFilters() throws Exception {
    Caller caller = aTenantWithTools("cursor-filters");

    JsonNode first = page(caller, 1, null, "attr.published:gte:100");
    String cursor = first.get("page").get("nextCursor").asString();
    assertThat(cursor).isNotBlank();

    // Same query, other filter. The list it was taken from no longer exists, so
    // resuming it would page through a sequence that never did. A malformed
    // request, the established answer for a cursor that does not belong here.
    mockMvc
        .perform(
            filtered(caller, 1, cursor, "attr.published:lt:2000")
                .session(caller.session()))
        .andExpect(status().isBadRequest());

    // And with the filter it was taken from, it resumes.
    mockMvc
        .perform(filtered(caller, 1, cursor, "attr.published:gte:100").session(caller.session()))
        .andExpect(status().isOk());
  }

  // -------------------------------------------------------------------------

  /**
   * Every name a filtered list gives, paging to the end.
   *
   * @param caller whose list
   * @param filters the {@code filter} parameters, all of which must hold
   * @return the names served, in the order they were served
   * @throws Exception when a request fails, which is the test failing
   */
  private List<String> namesOf(Caller caller, String... filters) throws Exception {
    List<String> names = new ArrayList<>();
    String cursor = null;
    do {
      JsonNode body = page(caller, 50, cursor, filters);
      body.get("data").forEach(item -> names.add(item.get("name").asString()));
      JsonNode next = body.get("page").get("nextCursor");
      cursor = next == null || next.isNull() ? null : next.asString();
    } while (cursor != null);
    return names;
  }

  /**
   * One page of a filtered list.
   *
   * @param caller whose list
   * @param limit how many at most
   * @param cursor where to resume, or {@code null} for the first page
   * @param filters the {@code filter} parameters
   * @return the parsed response body
   * @throws Exception when the request fails or does not answer {@code 200}
   */
  private JsonNode page(Caller caller, int limit, String cursor, String... filters)
      throws Exception {
    String body =
        mockMvc
            .perform(filtered(caller, limit, cursor, filters).session(caller.session()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    return json.readTree(body);
  }

  /**
   * Asserts that a filter is refused with a {@code 422} rather than quietly dropped.
   *
   * @param caller whose list
   * @param filter the parameter to refuse
   * @throws Exception when the request fails, which is the test failing
   */
  private void refused(Caller caller, String filter) throws Exception {
    mockMvc
        .perform(get(ITEMS).param("filter", filter).session(caller.session()))
        .andExpect(status().isUnprocessableContent());
  }

  /**
   * A list request carrying the filters, and a cursor where one is given.
   *
   * @param caller whose list
   * @param limit how many at most
   * @param cursor where to resume, or {@code null}
   * @param filters the {@code filter} parameters, repeated as the wire repeats them
   * @return the request
   */
  private MockHttpServletRequestBuilder filtered(
      Caller caller, int limit, String cursor, String... filters) {
    MockHttpServletRequestBuilder request =
        get(ITEMS).param("limit", String.valueOf(limit)).param("sort", "name");
    for (String filter : filters) {
      request = request.param("filter", filter);
    }
    return cursor == null ? request : request.param("cursor", cursor);
  }

  /**
   * A tenant, signed in, holding three tools that differ in every filterable field.
   *
   * <p>The three are chosen so that no single filter separates them the same way twice: two
   * currencies, two weights, three years and three manufacturers.
   *
   * @param name distinguishes this test's tenant from the others'
   * @return the caller
   * @throws Exception when provisioning or signing in fails
   */
  private Caller aTenantWithTools(String name) throws Exception {
    String email = "filtering-" + name + "@example.org";
    UUID userId = UUID.randomUUID();
    transactions.executeWithoutResult(
        status ->
            users.save(
                AppUser.create(
                    userId,
                    email,
                    "Filterer",
                    "en",
                    passwordEncoder.encode(PASSWORD),
                    Instant.now())));
    enrolSecondFactor(userId);
    UUID tenantId = provisioning.provision("Filtering " + name, userId);
    UUID typeId = TenantContext.callAs(tenantId, () -> toolType(userId));

    Caller caller = new Caller(signIn(email, PASSWORD), typeId);
    aTool(
        caller,
        "Hammer",
        """
        {"manufacturer":"Stanley",
         "serial":"H-1",
         "published":1999,
         "purchasePrice":{"amount":"24.90","currency":"EUR"},
         "netWeight":{"value":"450","unit":"g"}}
        """);
    aTool(
        caller,
        "Drill",
        """
        {"manufacturer":"Bosch",
         "serial":"D-2",
         "published":2018,
         "purchasePrice":{"amount":"129.00","currency":"EUR"},
         "netWeight":{"value":"1200","unit":"g"}}
        """);
    aTool(
        caller,
        "Spanner",
        """
        {"manufacturer":"Gedore",
         "serial":"S-3",
         "published":2004,
         "purchasePrice":{"amount":"150.00","currency":"USD"},
         "netWeight":{"value":"80","unit":"g"}}
        """);
    return caller;
  }

  /**
   * A published type whose fields cover every column a filter can reach, plus one that is not
   * searchable so that the refusal has something real to refuse.
   *
   * @param userId who is editing the type system
   * @return the published type's id
   */
  private UUID toolType(UUID userId) {
    TypeAdministration.ItemTypeView type =
        types.createItemType(
            new TypeAdministration.CreateItemTypeCommand("tool", TypeKind.DIGITAL, null, null),
            userId);
    types.addField(
        type.draftVersionId(), field("manufacturer", FieldDataType.TEXT, true), userId);
    types.addField(type.draftVersionId(), field("published", FieldDataType.INTEGER, true), userId);
    types.addField(type.draftVersionId(), field("purchasePrice", FieldDataType.MONEY, true), userId);
    types.addField(type.draftVersionId(), field("netWeight", FieldDataType.QUANTITY, true), userId);
    // Declared, stored, and deliberately not searchable: a filter on it is
    // refused because the tenant said so, not because the key is unknown.
    types.addField(type.draftVersionId(), field("serial", FieldDataType.TEXT, false), userId);
    types.publish(type.draftVersionId(), userId);
    return type.id();
  }

  /**
   * A field command with the one flag this test varies.
   *
   * @param key the attribute key
   * @param dataType the kind of value
   * @param searchable whether it is mirrored for filtering
   * @return the command
   */
  private TypeAdministration.FieldCommand field(
      String key, FieldDataType dataType, boolean searchable) {
    return new TypeAdministration.FieldCommand(
        key,
        dataType,
        Map.of("en", key),
        Map.of(),
        false,
        null,
        FieldConstraints.NONE,
        null,
        null,
        null,
        0,
        searchable,
        false,
        false,
        false);
  }

  /**
   * Creates one tool over HTTP, the way a client would.
   *
   * @param caller whose inventory
   * @param name the item's name
   * @param attributes the attribute set as JSON text
   * @throws Exception when the creation is not accepted
   */
  private void aTool(Caller caller, String name, String attributes) throws Exception {
    mockMvc
        .perform(
            post(ITEMS)
                .session(caller.session())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "name",
                            name,
                            "kind",
                            "DIGITAL",
                            "itemTypeId",
                            caller.typeId().toString(),
                            "attributes",
                            attributes))))
        .andExpect(status().isCreated());
  }

  /**
   * A signed-in caller and the type its items are written against.
   *
   * @param session the authenticated session
   * @param typeId the published type
   */
  private record Caller(MockHttpSession session, UUID typeId) {}
}
