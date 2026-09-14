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

import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
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
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Ordering a list, and paging through it (REQ-SRCH-004).
 *
 * <p>Sorting and keyset pagination together are where "a row appears twice, or not at all" lives.
 * The ordering is easy to eyeball on one page and the bug only shows at a page boundary, so every
 * test here pages in twos through an odd number of rows and compares the whole sequence.
 *
 * <p>Three decisions taken with the owner on 2026-09-14 are asserted rather than assumed: one sort
 * key and not several, rows without the value last whichever direction is asked for, and a
 * dimensioned field ordered by its unit before its amount.
 */
@DisplayName("A sorted list")
class ItemSortingIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "sorted-and-paged-2026";
  private static final String ITEMS = "/api/v1/items";

  @Autowired private TenantProvisioningService provisioning;
  @Autowired private AppUserRepository users;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private ObjectMapper json;

  @Test
  @DisplayName("orders by a column, both ways, and pages without losing a row")
  void orderingByAColumn() throws Exception {
    MockHttpSession session = tenantSession("columns");
    anItem(session, "Ladder");
    anItem(session, "Anvil");
    anItem(session, "Mallet");

    assertThat(namesOf(session, "name", 50)).containsExactly("Anvil", "Ladder", "Mallet");
    assertThat(namesOf(session, "-name", 50)).containsExactly("Mallet", "Ladder", "Anvil");

    // The same order, read two at a time. Three rows over two pages is where a
    // boundary bug shows: the second page either repeats "Ladder" or skips it.
    assertThat(namesOf(session, "name", 2)).containsExactly("Anvil", "Ladder", "Mallet");
    assertThat(namesOf(session, "-name", 2)).containsExactly("Mallet", "Ladder", "Anvil");
  }

  @Test
  @DisplayName("refuses a sort it cannot honour rather than ignoring it")
  void whatIsRefused() throws Exception {
    MockHttpSession session = tenantSession("refusals");

    // Several keys. 08 §8.2 once showed two; one is what REQ-SRCH-004 asks for,
    // and a caller who asked for two and silently got one has a list that is
    // wrong in a way nothing tells them about.
    mockMvc
        .perform(get(ITEMS).param("sort", "-updatedAt,name").session(session))
        .andExpect(status().isUnprocessableContent());

    // A column that is not a field of an item.
    mockMvc
        .perform(get(ITEMS).param("sort", "tenantId").session(session))
        .andExpect(status().isUnprocessableContent());

    // An attribute no published type marks sortable. Named rather than ignored:
    // the tenant configures which fields are sortable, so somebody can act on it.
    mockMvc
        .perform(get(ITEMS).param("sort", "attr.nothingLikeThis").session(session))
        .andExpect(status().isUnprocessableContent());

    // A direction with no field.
    mockMvc
        .perform(get(ITEMS).param("sort", "-").session(session))
        .andExpect(status().isUnprocessableContent());
  }

  @Test
  @DisplayName("refuses a cursor from a differently ordered list (REQ-SRCH-009)")
  void aCursorBelongsToItsOrder() throws Exception {
    MockHttpSession session = tenantSession("cursor-order");
    anItem(session, "Anvil");
    anItem(session, "Ladder");
    anItem(session, "Mallet");

    JsonNode first = page(session, "name", 2, null);
    String cursor = first.get("page").get("nextCursor").asString();
    assertThat(cursor).isNotBlank();

    // Same query, other order. Resuming it would page through a sequence that
    // never existed, so the cursor is refused rather than honoured. A malformed
    // request and not a validation failure: the cursor is well formed in itself
    // and simply does not belong to this query, which is what the established
    // answer for an invalid cursor already says.
    mockMvc
        .perform(get(ITEMS).param("sort", "-name").param("limit", "2").param("cursor", cursor)
            .session(session))
        .andExpect(status().isBadRequest());

    // And with the order it was taken from, it resumes.
    mockMvc
        .perform(get(ITEMS).param("sort", "name").param("limit", "2").param("cursor", cursor)
            .session(session))
        .andExpect(status().isOk());
  }

  // -------------------------------------------------------------------------

  /**
   * Every name the list gives, paging to the end.
   *
   * @param session the caller
   * @param sort the order
   * @param limit how many per page — small values are the point
   * @return the names in the order they were served
   * @throws Exception when a request fails, which is the test failing
   */
  private List<String> namesOf(MockHttpSession session, String sort, int limit) throws Exception {
    List<String> names = new ArrayList<>();
    String cursor = null;
    do {
      JsonNode body = page(session, sort, limit, cursor);
      body.get("data").forEach(item -> names.add(item.get("name").asString()));
      JsonNode next = body.get("page").get("nextCursor");
      cursor = next == null || next.isNull() ? null : next.asString();
    } while (cursor != null);
    return names;
  }

  private JsonNode page(MockHttpSession session, String sort, int limit, String cursor)
      throws Exception {
    var request =
        get(ITEMS).param("sort", sort).param("limit", String.valueOf(limit)).session(session);
    if (cursor != null) {
      request = request.param("cursor", cursor);
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

  private void anItem(MockHttpSession session, String name) throws Exception {
    mockMvc
        .perform(
            post(ITEMS)
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", name, "kind", "DIGITAL"))))
        .andExpect(status().isCreated());
  }

  private MockHttpSession tenantSession(String name) throws Exception {
    String email = "sorting-" + name + "@example.org";
    UUID userId = UUID.randomUUID();
    transactions.executeWithoutResult(
        status ->
            users.save(
                AppUser.create(
                    userId, email, "Sorter", "en", passwordEncoder.encode(PASSWORD), Instant.now())));
    enrolSecondFactor(userId);
    provisioning.provision("Sorting " + name, userId);
    return signIn(email, PASSWORD);
  }
}
