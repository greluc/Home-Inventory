/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.homeinv.identity.api.UserProvisioning;
import de.greluc.homeinv.tenancy.api.TenantProvisioning;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import tools.jackson.databind.ObjectMapper;

/**
 * A deployed instance can be signed in to (REQ-TEN-012, ADR-0053).
 *
 * <h2>What this is really checking</h2>
 *
 * <p>Until 2026-09-12 nothing outside a test could create an account or a tenant, so a deployed
 * instance had nobody who could sign in — while a test suite of this size proved every screen behind
 * the login worked. This drives the two ports the {@code bootstrap} one-shot calls and then signs in
 * with the result, which is the only order in which the gap was visible.
 *
 * <p>The runner itself is not exercised here: it ends with {@code System.exit}, which in a test would
 * take the JVM with it. What the runner adds beyond these two calls is reading three variables and
 * choosing an exit code, and the smoke suite runs the real thing in a real stack.
 */
@DisplayName("The first owner")
class FirstOwnerIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple-42";

  @Autowired private UserProvisioning users;
  @Autowired private TenantProvisioning tenants;
  @Autowired private ObjectMapper json;

  @Test
  @DisplayName("can sign in, and owns a tenant that can hold something")
  void theOwnerCanSignIn() throws Exception {
    String email = "first-owner@example.org";
    UUID userId = users.createIfAbsent(email, "Owner", "en", PASSWORD).orElseThrow();
    UUID tenantId = tenants.provision("Home", userId);

    MockHttpSession session = new MockHttpSession();
    mockMvc
        .perform(
            post("/api/v1/auth/login")
                .session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tenantId").value(tenantId.toString()))
        .andExpect(jsonPath("$.role").value("OWNER"))
        // The language the operator set, which the client starts in (REQ-NFR-033).
        .andExpect(jsonPath("$.locale").value("en"));

    // The tenant is usable and not merely present: its catalogue is seeded, which
    // is what an item and a location both need before they can exist at all.
    mockMvc
        .perform(get("/api/v1/locations/categories").session(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(13));
  }

  @Test
  @DisplayName("is created once, however often the service runs (REQ-TEN-012)")
  void aSecondRunChangesNothing() {
    String email = "idempotent-owner@example.org";

    Optional<UUID> first = users.createIfAbsent(email, "Owner", "en", PASSWORD);
    Optional<UUID> second = users.createIfAbsent(email, "Somebody Else", "de", "a-different-one");

    assertThat(first).isPresent();
    assertThat(second)
        .as(
            "a redeploy runs the bootstrap service again; it must not create a second owner and "
                + "must not reset a password the operator has since changed")
        .isEmpty();
  }

  @Test
  @DisplayName("is matched by its address whatever case it is typed in")
  void theAddressIsFoldedToLowerCase() {
    String email = "Mixed.Case.Owner@Example.ORG";

    assertThat(users.createIfAbsent(email, "Owner", "en", PASSWORD)).isPresent();
    // Every mail provider treats the local part as case-insensitive, so storing it
    // as typed would let the same person create a second account by capitalising
    // one letter and then fail to sign in with the other spelling.
    assertThat(users.createIfAbsent(email.toLowerCase(java.util.Locale.ROOT), "Owner", "en", PASSWORD))
        .isEmpty();
  }
}
