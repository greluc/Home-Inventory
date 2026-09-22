/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Too many requests, too fast, from one caller (REQ-SEC-064).
 *
 * <h2>Why the limits are lowered here</h2>
 *
 * <p>The shipped figures are chosen so that no honest client ever meets them — six hundred requests
 * a minute per account. A test that actually reached that would take a minute of the suite to prove
 * arithmetic. So the limits are set to something a loop crosses in a moment, and what is asserted
 * is the <b>behaviour</b>: the status, the two headers, the problem document, and that one caller's
 * flood does not refuse another caller.
 *
 * <p>The addresses differ per test, so each has a counter of its own and the suite's order cannot
 * decide its outcome. They are set on the request rather than as {@code X-Forwarded-For}: the
 * header is resolved by {@code TrustedForwardedHeaderFilter}, which is a servlet filter and not
 * part of the chain MockMvc builds — and a test that depended on it would be testing the filter.
 * What the limiter reads is {@code getRemoteAddr}, which is what this sets.
 */
@DisplayName("A caller going too fast")
@TestPropertySource(
    properties = {
      "homeinv.rate-limit.per-address-per-minute=5",
      "homeinv.rate-limit.auth-per-minute=3"
    })
class RateLimitIT extends AbstractIntegrationTest {

  /**
   * A request that appears to come from a given address.
   *
   * @param address the client address the limiter will count against
   * @return the post-processor that sets it
   */
  private static RequestPostProcessor from(String address) {
    return request -> {
      request.setRemoteAddr(address);
      return request;
    };
  }

  @Test
  @DisplayName("is refused with 429, Retry-After and a problem document")
  void theLimitIsReachedAndSaidSo() throws Exception {
    String address = "198.51.100.7";

    for (int attempt = 0; attempt < 5; attempt++) {
      mockMvc
          .perform(get("/api/v1/version").with(from(address)))
          .andReturn();
    }

    MvcResult refused =
        mockMvc.perform(get("/api/v1/version").with(from(address))).andReturn();

    assertThat(refused.getResponse().getStatus()).isEqualTo(429);
    assertThat(refused.getResponse().getHeader("Retry-After"))
        .as("the header every HTTP library already understands, not only a field in the body")
        .isNotNull()
        .satisfies(value -> assertThat(Integer.parseInt(value)).isBetween(1, 60));

    String body = refused.getResponse().getContentAsString();
    assertThat(JsonPath.<String>read(body, "$.type")).endsWith("rate-limited");
    assertThat(JsonPath.<Integer>read(body, "$.status")).isEqualTo(429);
    assertThat(JsonPath.<String>read(body, "$.traceId")).matches("[0-9a-f]{32}");
  }

  @Test
  @DisplayName("is told where it stands before it gets there")
  void everyResponseCarriesTheHeaders() throws Exception {
    MvcResult allowed =
        mockMvc
            .perform(get("/api/v1/version").with(from("198.51.100.8")))
            .andReturn();

    assertThat(allowed.getResponse().getStatus()).isEqualTo(200);
    // The IETF draft form, which is what the CORS configuration already exposes.
    // A client that reads these never has to meet a 429 to learn there is a
    // limit.
    assertThat(allowed.getResponse().getHeader("RateLimit"))
        .isNotNull()
        .matches("\"[a-z]+\";r=\\d+;t=\\d+");
    assertThat(allowed.getResponse().getHeader("RateLimit-Policy"))
        .isNotNull()
        .matches("\"[a-z]+\";q=\\d+;w=60");
  }

  @Test
  @DisplayName("does not spend anybody else's allowance")
  void oneCallerCannotRefuseAnother() throws Exception {
    String flooding = "198.51.100.9";
    for (int attempt = 0; attempt < 8; attempt++) {
      mockMvc.perform(get("/api/v1/version").with(from(flooding))).andReturn();
    }
    assertThat(
            mockMvc
                .perform(get("/api/v1/version").with(from(flooding)))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(429);

    // The point of keying per address at all. A limiter that counted every
    // request together would be an outage switch anybody could pull.
    assertThat(
            mockMvc
                .perform(get("/api/v1/version").with(from("198.51.100.10")))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(200);
  }

  @Test
  @DisplayName("meets a stricter limit on the authentication endpoints")
  void signingInIsHeldToItsOwnLimit() throws Exception {
    String address = "198.51.100.11";

    // Three attempts allowed here against five for everything else, so the
    // fourth is refused while a fourth request to any other endpoint would not
    // be. That difference IS the stricter bucket: without it, the endpoint an
    // attacker actually uses would be as generous as the item list.
    for (int attempt = 0; attempt < 3; attempt++) {
      mockMvc
          .perform(
              post("/api/v1/auth/login")
                  .with(from(address))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"email\":\"nobody@example.invalid\",\"password\":\"wrong-one\"}"))
          .andReturn();
    }

    MvcResult refused =
        mockMvc
            .perform(
                post("/api/v1/auth/login")
                    .with(from(address))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"nobody@example.invalid\",\"password\":\"wrong-one\"}"))
            .andReturn();

    assertThat(refused.getResponse().getStatus()).isEqualTo(429);
    assertThat(refused.getResponse().getHeader("Retry-After")).isNotNull();
  }
}
