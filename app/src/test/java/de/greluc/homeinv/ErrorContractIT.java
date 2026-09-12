/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * One error format on every surface, with a {@code traceId} (REQ-API-003, REQ-NFR-042).
 *
 * <h2>Why this is driven through HTTP rather than through the handler</h2>
 *
 * <p>Because the failures that mattered were the ones no handler ever saw. Until 2026-09-12 three
 * of the cases below answered with something else entirely: an unauthenticated request got a
 * {@code 401} with an empty body from Spring Security's entry point, an unknown path and an
 * unsupported method got Spring Boot's {@code /error} JSON, and an oversized JSON body was not
 * refused at all. Each of those is outside {@code ApiExceptionHandler}, so a test that called the
 * advice would have passed the whole time.
 *
 * <p>{@code REQ-API-003} says "uniform across every endpoint". Uniform is a property of the surface,
 * and only a request can observe it.
 */
@DisplayName("Every error")
class ErrorContractIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple-42";

  @Autowired private AppUserRepository users;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private ObjectMapper json;

  @Test
  @DisplayName("is problem+json when no session was presented (REQ-API-003)")
  void unauthenticatedIsAProblemDocument() throws Exception {
    MvcResult result =
        mockMvc
            .perform(get("/api/v1/items/" + UUID.randomUUID()))
            .andExpect(status().isUnauthorized())
            .andReturn();

    JsonNode problem = problemOf(result);
    assertThat(problem.get("type").asString())
        .isEqualTo("https://home-inv.example/problems/unauthenticated");
    assertThat(problem.get("status").asInt()).isEqualTo(401);
  }

  @Test
  @DisplayName("is problem+json for a path that does not exist (REQ-API-003)")
  void anUnknownPathIsAProblemDocument() throws Exception {
    // Signed in, because an anonymous request to an unknown path is a 401 and
    // that is deliberate: `anyRequest().authenticated()` decides before routing
    // does, so a stranger cannot map the surface by watching which paths answer
    // 404 and which 401.
    MockHttpSession session = tenantSession("unknown-path");

    MvcResult result =
        mockMvc
            .perform(get("/api/v1/nothing-here").session(session))
            .andExpect(status().isNotFound())
            .andReturn();

    JsonNode problem = problemOf(result);
    assertThat(problem.get("type").asString())
        .isEqualTo("https://home-inv.example/problems/not-found");
    // The occurrence, not the dispatch target. `/error` here would identify nothing.
    assertThat(problem.get("instance").asString()).isEqualTo("/api/v1/nothing-here");
  }

  @Test
  @DisplayName("is problem+json for a method the path does not support (REQ-API-003)")
  void aWrongMethodIsAProblemDocument() throws Exception {
    MockHttpSession session = tenantSession("wrong-method");

    MvcResult result =
        mockMvc
            .perform(post("/api/v1/items/" + UUID.randomUUID()).session(session).with(csrf()))
            .andExpect(status().isMethodNotAllowed())
            .andReturn();

    assertThat(problemOf(result).get("type").asString())
        .isEqualTo("https://home-inv.example/problems/method-not-allowed");
  }

  @Test
  @DisplayName("is problem+json for a content type nothing reads (REQ-API-003)")
  void anUnreadableContentTypeIsAProblemDocument() throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.TEXT_PLAIN)
                    .content("email=someone"))
            .andExpect(status().isUnsupportedMediaType())
            .andReturn();

    assertThat(problemOf(result).get("type").asString())
        .isEqualTo("https://home-inv.example/problems/unsupported-media-type");
  }

  @Test
  @DisplayName("carries a traceId that is in the log for the same request (REQ-NFR-042)")
  void everyErrorCarriesATraceId() throws Exception {
    MvcResult result =
        mockMvc
            .perform(get("/api/v1/items/" + UUID.randomUUID()))
            .andExpect(status().isUnauthorized())
            .andReturn();

    String traceId = problemOf(result).get("traceId").asString();
    // The W3C shape, because an OpenTelemetry agent will supply the same field at
    // stage 1 (REQ-NFR-044) and a client that learned to quote a 16-character id
    // would have to learn again.
    assertThat(traceId).matches("[0-9a-f]{32}");
  }

  @Test
  @DisplayName("adopts an incoming traceparent, so one request has one id end to end")
  void anIncomingTraceparentIsAdopted() throws Exception {
    String incoming = "4bf92f3577b34da6a3ce929d0e0e4736";

    MvcResult result =
        mockMvc
            .perform(
                get("/api/v1/items/" + UUID.randomUUID())
                    .header("traceparent", "00-" + incoming + "-00f067aa0ba902b7-01"))
            .andExpect(status().isUnauthorized())
            .andReturn();

    assertThat(problemOf(result).get("traceId").asString()).isEqualTo(incoming);
  }

  @Test
  @DisplayName("refuses a JSON body over one megabyte, as problem+json (REQ-SEC-065)")
  void anOversizedJsonBodyIsRefused() throws Exception {
    // Well-formed JSON, and far past the limit: the point is that it is refused
    // for its size rather than parsed and then rejected for its content.
    String body =
        "{\"email\":\"someone@example.org\",\"password\":\"" + "x".repeat(1_200_000) + "\"}";

    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isContentTooLarge())
            .andReturn();

    JsonNode problem = problemOf(result);
    assertThat(problem.get("type").asString())
        .isEqualTo("https://home-inv.example/problems/payload-too-large");
    assertThat(problem.get("limit").asLong()).isEqualTo(1_000_000L);
  }

  @Test
  @DisplayName("names the permission problem rather than the resource, for a caller who may look")
  void aDeniedPermissionIsAProblemDocument() throws Exception {
    MockHttpSession session = tenantSession("denied");

    MvcResult result =
        mockMvc
            .perform(get("/api/v1/items/" + UUID.randomUUID()).session(session))
            .andExpect(status().isNotFound())
            .andReturn();

    JsonNode problem = problemOf(result);
    assertThat(problem.get("type").asString())
        .isEqualTo("https://home-inv.example/problems/not-found");
    assertThat(problem.get("title").asString()).isEqualTo("Not found");
    assertThat(problem.get("traceId").asString()).isNotEmpty();
  }

  @Test
  @DisplayName("gives away no internals: no path, no SQL, no stack frame (REQ-SEC-067)")
  void noErrorRevealsInternals() throws Exception {
    MockHttpSession session = tenantSession("internals");

    // Four failures reached by four different routes, so the assertion is about
    // the contract rather than about one handler: a bad body, an unknown path, a
    // method the path does not serve, and a rejected permission.
    List<MvcResult> failures =
        List.of(
            mockMvc
                .perform(
                    post("/api/v1/items")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": "))
                .andReturn(),
            mockMvc.perform(get("/api/v1/does-not-exist").session(session)).andReturn(),
            mockMvc.perform(delete("/api/v1/items").session(session).with(csrf())).andReturn(),
            mockMvc.perform(get("/api/v1/items/" + UUID.randomUUID()).session(session)).andReturn());

    for (MvcResult result : failures) {
      String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
      assertThat(result.getResponse().getStatus())
          .as("the request was meant to fail: %s", body)
          .isGreaterThanOrEqualTo(400);

      // Each of these is something an exception message carries by default and a
      // caller must never receive (REQ-SEC-067, REQ-NFR-042). The traceId is what
      // connects their report to the log line that has all of it.
      assertThat(body)
          .as("a class or package name reached the caller: %s", body)
          .doesNotContain("de.greluc.homeinv")
          .doesNotContain("org.springframework")
          .doesNotContain("java.lang");
      assertThat(body)
          .as("a stack frame reached the caller: %s", body)
          .doesNotContain(".java:")
          .doesNotContain("\tat ");
      assertThat(body)
          .as("SQL reached the caller: %s", body)
          .doesNotContainIgnoringCase("select ")
          .doesNotContainIgnoringCase("insert into")
          .doesNotContainIgnoringCase("sqlstate");
      assertThat(body)
          .as("a filesystem path reached the caller: %s", body)
          .doesNotContain("/home/")
          .doesNotContain("C:\\\\");

      assertThat(problemOf(result).get("traceId").asString())
          .as("every error carries the id an operator can find it by")
          .isNotEmpty();
    }
  }

  // -------------------------------------------------------------------------

  /**
   * The body of a failed response, checked to be a problem document first.
   *
   * @param result the finished exchange
   * @return the parsed body
   * @throws Exception when the body cannot be read
   */
  private JsonNode problemOf(MvcResult result) throws Exception {
    assertThat(result.getResponse().getContentType())
        .as("every error is application/problem+json (REQ-API-003)")
        .startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  /**
   * A session belonging to the owner of a fresh tenant.
   *
   * @param name distinguishes this test's tenant and user from the others in the class
   * @return the signed-in session
   * @throws Exception when signing in fails
   */
  private MockHttpSession tenantSession(String name) throws Exception {
    String email = "error-contract-" + name + "@example.org";
    provisioning.provision("Error contract " + name, createUser(email));
    return login(email);
  }

  private MockHttpSession login(String email) throws Exception {
    MockHttpSession session = new MockHttpSession();
    mockMvc
        .perform(
            post("/api/v1/auth/login")
                .session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
        .andExpect(status().isOk());
    return session;
  }

  private UUID createUser(String email) {
    UUID userId = UUID.randomUUID();
    transactions.executeWithoutResult(
        status ->
            users.save(
                AppUser.create(
                    userId,
                    email,
                    "Error Contract",
                    "en",
                    passwordEncoder.encode(PASSWORD),
                    Instant.now())));
    return userId;
  }

  private static org.springframework.test.web.servlet.request.RequestPostProcessor csrf() {
    return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
        .csrf();
  }
}
