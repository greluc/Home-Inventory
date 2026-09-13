/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/**
 * The sessions an account has open, and ending one of them (REQ-AUTH-009).
 *
 * <h2>Why this test builds its own {@code MockMvc}</h2>
 *
 * <p>Every other test here passes a {@code MockHttpSession} object from call to call, which is the
 * cheap way to be signed in and never touches the session store. This one is <em>about</em> the
 * store: the list is read from it and the remote sign-out is a removal from it. So it puts Spring
 * Session's own filter in the chain and carries the cookies between requests, exactly as a browser
 * does — which means the sessions here really are in Valkey.
 */
@DisplayName("The session overview")
class SessionOverviewIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple-42";

  @Autowired private AppUserRepository users;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private ObjectMapper json;
  @Autowired private WebApplicationContext context;

  @BeforeEach
  void withTheSessionStore() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(
                context.getBean(de.greluc.homeinv.rest.TraceIdFilter.class),
                context.getBean(SessionRepositoryFilter.class))
            .apply(springSecurity())
            .build();
  }

  @Test
  @DisplayName("lists every device, names none of them by its session id, and ends one remotely")
  void twoSessionsAndARemoteSignOut() throws Exception {
    account("sessions@example.org");
    Cookie[] phone = signInAs("sessions@example.org", "A phone");
    Cookie[] laptop = signInAs("sessions@example.org", "A laptop");

    String body =
        mockMvc
            .perform(get("/api/v1/me/sessions").cookie(laptop))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andReturn()
            .getResponse()
            .getContentAsString();

    // The list carries no session id. One that did would be a list of working
    // cookies, and this is the assertion that keeps it that way.
    assertThat(body).doesNotContain(sessionIdIn(phone)).doesNotContain(sessionIdIn(laptop));
    assertThat(body).contains("A phone").contains("A laptop");

    // The caller's own session is marked, so a client does not offer to end it by
    // accident.
    mockMvc
        .perform(get("/api/v1/me/sessions").cookie(laptop))
        .andExpect(jsonPath("$[?(@.current == true)].device").value("A laptop"));

    String phoneHandle =
        json.readTree(body).valueStream()
            .filter(entry -> "A phone".equals(entry.get("device").asString()))
            .findFirst()
            .orElseThrow()
            .get("handle")
            .asString();

    mockMvc
        .perform(delete("/api/v1/me/sessions/" + phoneHandle).cookie(laptop).with(csrf()))
        .andExpect(status().isNoContent());

    // The phone's next request has no session at all.
    mockMvc.perform(get("/api/v1/me/sessions").cookie(phone)).andExpect(status().isUnauthorized());

    // And the laptop sees itself alone.
    mockMvc
        .perform(get("/api/v1/me/sessions").cookie(laptop))
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].device").value("A laptop"));
  }

  @Test
  @DisplayName("shows a network rather than an address, and nobody else's sessions")
  void whatTheListSaysAndWhatItDoesNot() throws Exception {
    account("sessions-privacy@example.org");
    account("sessions-other@example.org");
    Cookie[] mine = signInAs("sessions-privacy@example.org", "Mine");
    signInAs("sessions-other@example.org", "Somebody else's");

    mockMvc
        .perform(get("/api/v1/me/sessions").cookie(mine))
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].device").value("Mine"))
        // REQ-PRIV-006: the host part is gone. MockMvc signs in from 127.0.0.1.
        .andExpect(jsonPath("$[0].origin").value("127.0.0.0/24"));

    // A handle that names nothing in this account's own list is not found rather
    // than refused: outside it there is nothing to refuse.
    mockMvc
        .perform(delete("/api/v1/me/sessions/AAAAAAAAAAAAAAAAAAAAAA").cookie(mine).with(csrf()))
        .andExpect(status().isNotFound());
  }

  // -------------------------------------------------------------------------

  /**
   * Signs in the way a device with its own name would, and keeps its cookies.
   *
   * @param email the address
   * @param device what the client calls itself
   * @return the cookies the browser would now hold
   * @throws Exception when the call fails, which is the test failing
   */
  private Cookie[] signInAs(String email, String device) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/auth/login")
                    .header("User-Agent", device)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
            .andExpect(status().isOk())
            .andReturn();
    return result.getResponse().getCookies();
  }

  /**
   * The session id a set of cookies carries.
   *
   * @param cookies what the browser holds
   * @return the id, for asserting that it appears nowhere in an answer
   */
  private static String sessionIdIn(Cookie[] cookies) {
    for (Cookie cookie : cookies) {
      if (cookie.getName().endsWith("homeinv-session")) {
        return cookie.getValue();
      }
    }
    throw new AssertionError("The login set no session cookie.");
  }

  private UUID account(String email) {
    UUID userId = UUID.randomUUID();
    transactions.executeWithoutResult(
        status ->
            users.save(
                AppUser.create(
                    userId, email, "Test", "en", passwordEncoder.encode(PASSWORD),
                    Instant.now())));
    // No second factor: the login is then one call, and `/api/v1/me/**` answers
    // for an owner who has not enrolled — which is what REQ-AUTH-003's lock
    // leaves reachable, and what this test needs.
    provisioning.provision("Tenant of " + email, userId);
    return userId;
  }

  private static RequestPostProcessor csrf() {
    return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
        .csrf();
  }

}
