/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Who may call this API from a browser, and who may not (REQ-SEC-062).
 *
 * <p>In the shipped topology the answer is "nobody cross-origin": {@code web} proxies {@code /api}
 * on the same origin as the shell. These tests exist because that will not always be obvious to
 * whoever debugs an integration, and {@code *} is what gets reached for at that moment.
 */
class CorsIT extends AbstractIntegrationTest {

  @Autowired private Environment environment;

  @Test
  @DisplayName("the configured origin is allowed, and is named rather than reflected")
  void theConfiguredOriginIsAllowed() throws Exception {
    String origin = environment.getRequiredProperty("homeinv.public-base-url");

    MockHttpServletResponse response =
        mockMvc
            .perform(
                options("/api/v1/items")
                    .header("Origin", origin)
                    .header("Access-Control-Request-Method", "POST"))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo(origin);
    // Credentials and a reflected origin are only dangerous together, and this is
    // the half that must stay a literal.
    assertThat(response.getHeader("Access-Control-Allow-Credentials")).isEqualTo("true");
  }

  @Test
  @DisplayName("another origin is refused, not reflected back")
  void aForeignOriginIsRefused() throws Exception {
    MockHttpServletResponse response =
        mockMvc
            .perform(
                options("/api/v1/items")
                    .header("Origin", "https://evil.example.org")
                    .header("Access-Control-Request-Method", "POST"))
            .andReturn()
            .getResponse();

    // A reflecting configuration answers 200 with the caller's own origin, which
    // together with allowCredentials lets any site read a logged-in user's data.
    assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
    assertThat(response.getStatus()).isEqualTo(403);
  }

  @Test
  @DisplayName("the wildcard never appears, whatever the caller asks for")
  void theWildcardIsNeverSent() throws Exception {
    for (String origin : new String[] {"*", "null", "https://another.example.org"}) {
      MockHttpServletResponse response =
          mockMvc
              .perform(
                  options("/api/v1/items")
                      .header("Origin", origin)
                      .header("Access-Control-Request-Method", "GET"))
              .andReturn()
              .getResponse();

      assertThat(response.getHeader("Access-Control-Allow-Origin"))
          .as("origin %s", origin)
          .isNotEqualTo("*");
    }
  }

  @Test
  @DisplayName("the headers a client is allowed to read back are named")
  void contractCarryingHeadersAreExposed() throws Exception {
    String origin = environment.getRequiredProperty("homeinv.public-base-url");

    MockHttpServletResponse response =
        mockMvc
            .perform(
                options("/api/v1/items")
                    .header("Origin", origin)
                    .header("Access-Control-Request-Method", "GET"))
            .andReturn()
            .getResponse();

    // Without this list a browser hides every response header, including the ones
    // 08 §8.2 puts contracts on - and a client that cannot read `RateLimit` cannot
    // honour it.
    String exposed = response.getHeader("Access-Control-Expose-Headers");
    assertThat(exposed).contains("ETag").contains("RateLimit").contains("Retry-After");
  }
}
