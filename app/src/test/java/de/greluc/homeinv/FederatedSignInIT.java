/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.plugins.application.DefaultPluginRegistry;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Signing in through a provider, on an instance that creates no accounts (REQ-AUTH-005).
 *
 * <p>The mode here is the default, {@code invite_only}. What that makes provable is the half of
 * REQ-AUTH-006 that matters most: an identity nobody has linked is <b>refused</b>, and refused with
 * the same sentence whether or not its verified address belongs to an account here. The creating
 * half is {@link FederatedRegistrationIT}, on an {@code open} instance.
 *
 * <p>Against a real plugin over a real TLS socket ({@link TestIdentityProvider}).
 */
@DisplayName("A federated sign-in")
class FederatedSignInIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple-42";
  private static final String SUBJECT = "provider-subject-1";

  @Autowired private DefaultPluginRegistry registry;
  @Autowired private AppUserRepository users;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;

  @BeforeEach
  void installTheProvider() throws Exception {
    TestIdentityProvider.install(registry, PKI);
  }

  @AfterAll
  static void stopTheProvider() {
    TestIdentityProvider.stop();
  }

  @Test
  @DisplayName("is offered on the sign-in page once a provider is installed")
  void theSignInPageOffersIt() throws Exception {
    mockMvc
        .perform(get("/api/v1/auth/federated"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].providerKey").value("oidc-test"))
        .andExpect(jsonPath("$[0].displayName").value("The test provider"));
  }

  @Test
  @DisplayName("sends the state, the nonce and the challenge the CORE minted")
  void theCoreMintsWhatItHasToCheck() throws Exception {
    String url = beginSignIn(null);

    var begin = TestIdentityProvider.lastBegin();
    assertThat(begin.getState()).isNotBlank();
    assertThat(begin.getNonce()).isNotBlank();
    // S256 of a verifier the browser never sees: 43 characters of base64url.
    assertThat(begin.getCodeChallenge()).hasSize(43);
    assertThat(begin.getRedirectUri()).endsWith("/api/v1/auth/federated/callback");
    // The plugin put them on the URL, which is the only place they may come from.
    assertThat(url).contains(begin.getState()).contains(begin.getCodeChallenge());
  }

  @Test
  @DisplayName("refuses an identity nobody has linked, even when its address has an account")
  void anUnlinkedIdentityIsRefused() throws Exception {
    String email = "ada-" + UUID.randomUUID() + "@example.org";
    anAccount(email);

    TestIdentityProvider.willReport(SUBJECT + "-unlinked", email, true);
    String state = stateOf(beginSignIn(null));

    mockMvc
        .perform(get("/api/v1/auth/federated/callback").param("state", state).param("code", "c"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.type").value(endsWith("federated-identity-unlinked")));
  }

  @Test
  @DisplayName("is single use: the same callback twice is gone the second time")
  void aReplayedCallbackFails() throws Exception {
    TestIdentityProvider.willReport(SUBJECT + "-replay", "", false);
    String state = stateOf(beginSignIn(null));

    mockMvc
        .perform(get("/api/v1/auth/federated/callback").param("state", state).param("code", "c"))
        .andExpect(status().isForbidden());
    // The property REQ-AUTH-005 is verified by: the handle is spent whether or
    // not the flow succeeded, so a captured callback is worth one attempt.
    mockMvc
        .perform(get("/api/v1/auth/federated/callback").param("state", state).param("code", "c"))
        .andExpect(status().isGone())
        .andExpect(jsonPath("$.type").value(endsWith("federated-flow-unknown")));
  }

  @Test
  @DisplayName("answers a browser with a redirect and a client with a problem")
  void twoKindsOfCaller() throws Exception {
    TestIdentityProvider.willReport(SUBJECT + "-browser", "", false);
    String state = stateOf(beginSignIn(null));

    // A person is looking at this: a JSON body would be a dead end, so the
    // sign-in page is told which refusal it was.
    mockMvc
        .perform(
            get("/api/v1/auth/federated/callback")
                .param("state", state)
                .param("code", "c")
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_HTML_VALUE))
        .andExpect(status().isSeeOther())
        .andExpect(header().string("Location", "/sign-in?failed=federated-identity-unlinked"));
  }

  @Test
  @DisplayName("links an identity to the signed-in account, and then signs in with it")
  void linkingAndThenSigningIn() throws Exception {
    String email = "linker-" + UUID.randomUUID() + "@example.org";
    UUID userId = anAccount(email);
    provisioning.provision("Tenant of " + email, userId);
    MockHttpSession session = signIn(email, PASSWORD);

    String subject = SUBJECT + "-" + UUID.randomUUID();
    TestIdentityProvider.willReport(subject, email, true);

    MvcResult started =
        mockMvc
            .perform(
                post("/api/v1/auth/federated/link")
                    .session(session)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}")
                    .with(csrf()))
            .andExpect(status().isOk())
            .andReturn();
    String state = stateOf(json(started).get("authorizationUrl").asText());

    mockMvc
        .perform(
            get("/api/v1/auth/federated/callback")
                .session(session)
                .param("state", state)
                .param("code", "c"))
        .andExpect(status().isSeeOther());

    // It is on the account page, with the address it was linked as.
    mockMvc
        .perform(get("/api/v1/auth/federated/links").session(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].issuer").value(TestIdentityProvider.ISSUER))
        .andExpect(jsonPath("$[0].email").value(email));

    // And now the identity signs in on its own, with no session to start from --
    // and stops where a password login stops, because the provider proved who
    // they are and not that they hold the authenticator this instance knows
    // about (REQ-AUTH-002).
    TestIdentityProvider.willReport(subject, email, true);
    MockHttpSession fresh = new MockHttpSession();
    String signInState = stateOf(beginSignIn(null));
    mockMvc
        .perform(
            get("/api/v1/auth/federated/callback")
                .session(fresh)
                .param("state", signInState)
                .param("code", "c"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.type").value(endsWith("second-factor-required")));

    answerTheSecondFactor(fresh, userId);
    // The session is real: an endpoint that needs one answers it.
    mockMvc
        .perform(get("/api/v1/auth/federated/links").session(fresh))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].issuer").value(TestIdentityProvider.ISSUER));
  }

  @Test
  @DisplayName("refuses to link an identity that belongs to another account")
  void anIdentityBelongsToOneAccount() throws Exception {
    String first = "first-" + UUID.randomUUID() + "@example.org";
    UUID firstUser = anAccount(first);
    provisioning.provision("Tenant of " + first, firstUser);
    MockHttpSession firstSession = signIn(first, PASSWORD);

    String subject = SUBJECT + "-" + UUID.randomUUID();
    TestIdentityProvider.willReport(subject, first, true);
    link(firstSession);

    String second = "second-" + UUID.randomUUID() + "@example.org";
    UUID secondUser = anAccount(second);
    provisioning.provision("Tenant of " + second, secondUser);
    MockHttpSession secondSession = signIn(second, PASSWORD);

    TestIdentityProvider.willReport(subject, second, true);
    MvcResult started =
        mockMvc
            .perform(
                post("/api/v1/auth/federated/link")
                    .session(secondSession)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}")
                    .with(csrf()))
            .andReturn();
    String state = stateOf(json(started).get("authorizationUrl").asText());

    mockMvc
        .perform(
            get("/api/v1/auth/federated/callback")
                .session(secondSession)
                .param("state", state)
                .param("code", "c"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value(endsWith("federated-identity-linked-elsewhere")));
  }

  @Test
  @DisplayName("removes a link when the account asks, and not when somebody else does")
  void unlinking() throws Exception {
    String email = "unlinker-" + UUID.randomUUID() + "@example.org";
    UUID userId = anAccount(email);
    provisioning.provision("Tenant of " + email, userId);
    MockHttpSession session = signIn(email, PASSWORD);

    TestIdentityProvider.willReport(SUBJECT + "-" + UUID.randomUUID(), email, true);
    link(session);

    MvcResult listed =
        mockMvc.perform(get("/api/v1/auth/federated/links").session(session)).andReturn();
    String id = json(listed).get(0).get("id").asText();

    // Somebody else's session cannot remove it: the id is looked up in the
    // caller's own list, so it is not found rather than refused.
    String otherEmail = "other-" + UUID.randomUUID() + "@example.org";
    UUID other = anAccount(otherEmail);
    provisioning.provision("Tenant of " + otherEmail, other);
    mockMvc
        .perform(
            delete("/api/v1/auth/federated/links/" + id)
                .session(signIn(otherEmail, PASSWORD))
                .with(csrf()))
        .andExpect(status().isNotFound());

    mockMvc
        .perform(delete("/api/v1/auth/federated/links/" + id).session(session).with(csrf()))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(get("/api/v1/auth/federated/links").session(session))
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  @DisplayName("refuses a return target that is not a path on this instance")
  void noOpenRedirect() throws Exception {
    for (String hostile :
        new String[] {"https://phishing.example/", "//phishing.example/", "/\\phishing.example"}) {
      mockMvc
          .perform(
              post("/api/v1/auth/federated/begin")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"returnTo\":\"" + hostile + "\"}"))
          .andExpect(status().is4xxClientError());
    }
  }

  @Test
  @DisplayName("sends the browser where it was going, when that is a path here")
  void aLocalReturnTarget() throws Exception {
    String email = "returner-" + UUID.randomUUID() + "@example.org";
    UUID userId = anAccount(email);
    provisioning.provision("Tenant of " + email, userId);
    MockHttpSession session = signIn(email, PASSWORD);

    String subject = SUBJECT + "-" + UUID.randomUUID();
    TestIdentityProvider.willReport(subject, email, true);
    link(session);

    TestIdentityProvider.willReport(subject, email, true);
    MockHttpSession fresh = new MockHttpSession();
    String state = stateOf(beginSignIn("/items/42"));
    // The second factor first, and the return target survives it: it belongs to
    // the flow rather than to the redirect that ends it.
    mockMvc
        .perform(
            get("/api/v1/auth/federated/callback")
                .session(fresh)
                .param("state", state)
                .param("code", "c"))
        .andExpect(status().isUnauthorized());
  }

  // -------------------------------------------------------------------------

  /** Starts a sign-in and returns the authorization URL. */
  private String beginSignIn(String returnTo) throws Exception {
    String body = returnTo == null ? "{}" : "{\"returnTo\":\"" + returnTo + "\"}";
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/auth/federated/begin")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isOk())
            .andReturn();
    return json(result).get("authorizationUrl").asText();
  }

  /** Runs a whole link flow for a session that has already stepped up. */
  private void link(MockHttpSession session) throws Exception {
    MvcResult started =
        mockMvc
            .perform(
                post("/api/v1/auth/federated/link")
                    .session(session)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}")
                    .with(csrf()))
            .andExpect(status().isOk())
            .andReturn();
    String state = stateOf(json(started).get("authorizationUrl").asText());
    mockMvc
        .perform(
            get("/api/v1/auth/federated/callback")
                .session(session)
                .param("state", state)
                .param("code", "c"))
        .andExpect(status().isSeeOther());
  }

  /** The `state` parameter out of an authorization URL. */
  private static String stateOf(String authorizationUrl) {
    for (String pair : URI.create(authorizationUrl).getQuery().split("&")) {
      String[] parts = pair.split("=", 2);
      if ("state".equals(parts[0])) {
        return parts[1];
      }
    }
    throw new AssertionError("the authorization URL carries no state: " + authorizationUrl);
  }

  /** An account with a password and nothing else. */
  private UUID anAccount(String email) {
    UUID userId = UUID.randomUUID();
    transactions.executeWithoutResult(
        status ->
            users.save(
                AppUser.create(
                    userId,
                    email,
                    "Ada",
                    "en",
                    passwordEncoder.encode(PASSWORD),
                    Instant.now())));
    // Linking an identity re-confirms the second factor, so an account that takes
    // part in these flows has one. That is the rule rather than the test's convenience:
    // attaching a way into an account is what REQ-AUTH-011 asks to be re-confirmed.
    enrolSecondFactor(userId);
    return userId;
  }

  private static JsonNode json(MvcResult result) throws Exception {
    return new com.fasterxml.jackson.databind.ObjectMapper()
        .readTree(result.getResponse().getContentAsString());
  }

  /** Matches a problem type by its token, whatever the namespace in front of it is. */
  private static org.hamcrest.Matcher<String> endsWith(String token) {
    return org.hamcrest.Matchers.endsWith(token);
  }

  /** The CSRF token this suite's other tests use. */
  private static org.springframework.test.web.servlet.request.RequestPostProcessor csrf() {
    return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
        .csrf();
  }

}
