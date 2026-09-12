/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.homeinv.identity.api.UserProvisioning;
import de.greluc.homeinv.tenancy.api.TenantProvisioning;
import jakarta.servlet.Filter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/**
 * The session cookie carries the attributes that make it hard to steal (REQ-SEC-017).
 *
 * <h2>Why a test and not a reading of the configuration</h2>
 *
 * <p>Because the attributes are set in one place and can be lost in several. Spring Session's
 * serialiser decides {@code Secure} from {@code request.isSecure()} unless it is told otherwise, a
 * servlet container can be configured to add a {@code Domain}, and any of that turns a cookie a
 * browser refuses into a cookie a browser accepts and sends where it should not. The gate
 * REQ-SEC-017 names is "cookie attributes verified", and what is verified here is the header that
 * actually leaves.
 *
 * <p>The {@code __Host-} prefix is the load-bearing one. A browser refuses a cookie with that name
 * unless it is {@code Secure}, path {@code /} and carries <em>no</em> {@code Domain} — so the name
 * is not decoration, it is the browser enforcing three of the other attributes on our behalf.
 */
@DisplayName("The session cookie")
class SessionCookieIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple-42";

  @Autowired private UserProvisioning users;
  @Autowired private TenantProvisioning tenants;
  @Autowired private ObjectMapper json;

  /**
   * The filter that writes the cookie, which the shared MockMvc does not carry.
   *
   * <p>`webAppContextSetup` registers none of the application's servlet filters, and Spring
   * Session's is where the `Set-Cookie` header comes from — so the shared instance never emits one
   * and every other test passes a `MockHttpSession` in by hand. This test is about the header, so it
   * builds its own chain with that filter in it.
   */
  @Autowired
  @Qualifier("springSessionRepositoryFilter")
  private Filter sessions;

  @Autowired private WebApplicationContext context;

  private MockMvc withSessions;

  @BeforeEach
  void buildChainWithSessions() {
    withSessions =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(sessions)
            .apply(springSecurity())
            .build();
  }

  @Test
  @DisplayName("is __Host-prefixed, Secure, HttpOnly and SameSite=Strict (REQ-SEC-017)")
  void theAttributesAreWhatTheRequirementAsksFor() throws Exception {
    String email = "session-cookie@example.org";
    UUID userId = users.createIfAbsent(email, "Owner", "en", PASSWORD).orElseThrow();
    tenants.provision("Cookies", userId);

    MvcResult result =
        withSessions
            .perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
            .andExpect(status().isOk())
            .andReturn();

    List<String> cookies = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
    String session =
        cookies.stream()
            .filter(header -> header.startsWith("__Host-homeinv-session="))
            .findFirst()
            .orElseThrow(
                () ->
                    new AssertionError(
                        "no __Host-homeinv-session cookie was set; the headers were " + cookies));

    assertThat(session).as("unreadable by script, so an XSS cannot take it").contains("HttpOnly");
    assertThat(session)
        .as("Secure, which the __Host- prefix also requires of a browser")
        .contains("Secure");
    assertThat(session)
        .as("not sent on a cross-site request at all (REQ-SEC-017)")
        .contains("SameSite=Strict");
    assertThat(session).as("path /, which the __Host- prefix requires").contains("Path=/");
    assertThat(session)
        .as("no Domain: a browser refuses a __Host- cookie that carries one")
        .doesNotContain("Domain=");

  }

  /*
   * The other half of REQ-SEC-017 — that a client which has only read holds a token it can then
   * write with — is checked in `deploy/smoke/journey.sh`, against a running stack: it signs in,
   * reads the categories, and creates a location with the token those responses left in its cookie
   * jar. It was a test here and it belongs there.
   *
   * Spring Security defers the CSRF token, and which response resolves it depends on what the
   * request touched. Through MockMvc that made the assertion depend on the order the suite happened
   * to run in — green alone, red in the full suite, and the other way round on the next run. A test
   * that passes for a reason nobody can name is worse than no test: the property is about a browser
   * making four requests in a row, and the journey makes them.
   *
   * `CsrfCookieFilter` is what makes it hold. Without it a client that signs in and reads has no
   * token at all, and its first write is refused with a 403 it can do nothing about.
   */
}
