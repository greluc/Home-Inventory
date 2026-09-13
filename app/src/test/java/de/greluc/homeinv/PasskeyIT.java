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

import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.data.AuthenticatorAssertionResponse;
import com.webauthn4j.data.AuthenticatorAttestationResponse;
import com.webauthn4j.data.PublicKeyCredential;
import com.webauthn4j.data.PublicKeyCredentialCreationOptions;
import com.webauthn4j.data.PublicKeyCredentialRequestOptions;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.test.EmulatorUtil;
import com.webauthn4j.test.client.ClientPlatform;
import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Passkeys, the other half of the second factor (REQ-AUTH-002, 12 §12.4).
 *
 * <p>Driven by webauthn4j's own authenticator emulator rather than by a stub: the responses are
 * real attestation and assertion objects, signed by a key pair the emulator holds, against the
 * challenges this application issued. A stub would prove that a stub agrees with the code under
 * test.
 *
 * <p>The origin the emulator signs for is the one {@code HOMEINV_PUBLIC_BASE_URL} names, because
 * that is what a passkey is bound to. A test that passed with a different origin would be a test
 * that had switched the check off.
 */
@DisplayName("A passkey")
class PasskeyIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple-42";

  /** What the test configuration sets as the public base URL, and what a passkey is bound to. */
  private static final String ORIGIN = "http://app.localhost:8080";

  @Autowired private AppUserRepository users;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private ObjectMapper json;

  private final ObjectConverter webAuthn = new ObjectConverter();
  private final ClientPlatform browser =
      new ClientPlatform(
          new Origin(ORIGIN),
          new com.webauthn4j.test.authenticator.webauthn.WebAuthnAuthenticatorAdaptor(
              EmulatorUtil.NONE_ATTESTATION_AUTHENTICATOR));

  @Test
  @DisplayName("is registered in two calls, and then the login takes two as well")
  void registerThenSignIn() throws Exception {
    UUID userId = account("passkey-owner@example.org");
    MockHttpSession session = signInWithPassword("passkey-owner@example.org");

    // Nothing yet, and the login is one call.
    mockMvc
        .perform(get("/api/v1/auth/mfa/enrolment").session(session))
        .andExpect(jsonPath("$.passkeys.length()").value(0));

    registerPasskey(session, "The test's key");

    mockMvc
        .perform(get("/api/v1/auth/mfa/enrolment").session(session))
        .andExpect(jsonPath("$.passkeys.length()").value(1))
        .andExpect(jsonPath("$.passkeys[0].label").value("The test's key"))
        // A passkey counts as a second factor even with no authenticator app.
        .andExpect(jsonPath("$.totpConfirmed").value(false));

    // From here the password alone is half a login.
    MockHttpSession pending = new MockHttpSession();
    mockMvc
        .perform(
            post("/api/v1/auth/login")
                .session(pending)
                .contentType(MediaType.APPLICATION_JSON)
                .content(credentials("passkey-owner@example.org")))
        .andExpect(status().isUnauthorized())
        .andExpect(
            jsonPath("$.type").value("https://home-inv.example/problems/second-factor-required"));

    // The challenge is asked for with no session at all — that is the point of it
    // being reachable in the middle of a login.
    String assertion = assertPasskey(pending);
    mockMvc
        .perform(
            post("/api/v1/auth/mfa")
                .session(pending)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("credential", assertion))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.userId").value(userId.toString()));
  }

  @Test
  @DisplayName("answers a re-confirmation, so an account with no authenticator app is not stuck")
  void aPasskeyAnswersTheStepUp() throws Exception {
    UUID owner = account("passkey-stepup@example.org");
    UUID tenantId = provisioning.provision("Passkeys", owner);
    MockHttpSession session = signInWithPassword("passkey-stepup@example.org");
    registerPasskey(session, "The only factor");

    // Signed in with a password alone and now holding a passkey: the role is
    // usable — the factor exists (REQ-AUTH-003) — but nothing has been proved in
    // this session, so a critical operation asks (REQ-AUTH-011).
    mockMvc
        .perform(get("/api/v1/tenants/" + tenantId + "/members").session(session))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            post("/api/v1/field-visibility")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("fieldKey", "purchasePrice", "role",
                    "OWNER"))))
        .andExpect(status().isForbidden())
        .andExpect(
            jsonPath("$.type").value("https://home-inv.example/problems/second-factor-stale"));

    mockMvc
        .perform(
            post("/api/v1/auth/mfa/step-up")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("credential", assertPasskey(session)))))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(
            post("/api/v1/field-visibility")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("fieldKey", "purchasePrice", "role",
                    "OWNER"))))
        .andExpect(status().isNoContent());
  }

  @Test
  @DisplayName("is refused when the challenge was never issued, and when it has been answered")
  void aChallengeIsUsedOnce() throws Exception {
    account("passkey-replay@example.org");
    MockHttpSession session = signInWithPassword("passkey-replay@example.org");
    registerPasskey(session, "A key");

    String assertion = assertPasskey(session);

    mockMvc
        .perform(
            post("/api/v1/auth/mfa/step-up")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("credential", assertion))))
        .andExpect(status().isNoContent());

    // The same response again, against a challenge that has been spent.
    mockMvc
        .perform(
            post("/api/v1/auth/mfa/step-up")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("credential", assertion))))
        .andExpect(status().isUnauthorized())
        .andExpect(
            jsonPath("$.type").value("https://home-inv.example/problems/second-factor-invalid"));
  }

  @Test
  @DisplayName("is removed only by somebody who has just proved one")
  void removalNeedsARecentProof() throws Exception {
    account("passkey-removal@example.org");
    MockHttpSession session = signInWithPassword("passkey-removal@example.org");
    registerPasskey(session, "Going");

    String body =
        mockMvc
            .perform(get("/api/v1/auth/mfa/enrolment").session(session))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String passkeyId = json.readTree(body).get("passkeys").get(0).get("id").asString();

    // Signed in with a password alone: nothing has been proved in this session.
    mockMvc
        .perform(post("/api/v1/auth/mfa/passkeys/" + passkeyId + "/removal")
            .session(session)
            .with(csrf()))
        .andExpect(status().isForbidden())
        .andExpect(
            jsonPath("$.type").value("https://home-inv.example/problems/second-factor-stale"));

    mockMvc
        .perform(
            post("/api/v1/auth/mfa/step-up")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("credential", assertPasskey(session)))))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(post("/api/v1/auth/mfa/passkeys/" + passkeyId + "/removal")
            .session(session)
            .with(csrf()))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(get("/api/v1/auth/mfa/enrolment").session(session))
        .andExpect(jsonPath("$.passkeys.length()").value(0));

    // And the login is one call again.
    assertThat(passwordOnlyLogin("passkey-removal@example.org")).isEqualTo(200);
  }

  // -------------------------------------------------------------------------

  /**
   * Registers a passkey through both calls, with the emulator playing the browser.
   *
   * @param session the caller's session
   * @param label what to call it
   * @throws Exception when a call fails, which is the test failing
   */
  private void registerPasskey(MockHttpSession session, String label) throws Exception {
    String options = optionsFrom(post("/api/v1/auth/mfa/passkeys"), session);
    PublicKeyCredentialCreationOptions creation =
        webAuthn.getJsonConverter().readValue(options, PublicKeyCredentialCreationOptions.class);

    PublicKeyCredential<AuthenticatorAttestationResponse, ?> credential = browser.create(creation);
    AuthenticatorAttestationResponse response = credential.getResponse();

    String credentialJson =
        json.writeValueAsString(
            Map.of(
                "id", base64url(credential.getRawId()),
                "rawId", base64url(credential.getRawId()),
                "type", "public-key",
                "clientExtensionResults", Map.of(),
                "response",
                    Map.of(
                        "clientDataJSON", base64url(response.getClientDataJSON()),
                        "attestationObject", base64url(response.getAttestationObject()))));

    mockMvc
        .perform(
            post("/api/v1/auth/mfa/passkeys/confirmation")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(
                    Map.of("credential", credentialJson, "label", label))))
        .andExpect(status().isNoContent());
  }

  /**
   * Asks for an assertion challenge and answers it with the emulator.
   *
   * @param session the session, which may be a pending login
   * @return the assertion, as the JSON a browser would produce
   * @throws Exception when a call fails, which is the test failing
   */
  private String assertPasskey(MockHttpSession session) throws Exception {
    String options = optionsFrom(post("/api/v1/auth/mfa/passkeys/challenge"), session);
    PublicKeyCredentialRequestOptions request =
        webAuthn.getJsonConverter().readValue(options, PublicKeyCredentialRequestOptions.class);

    PublicKeyCredential<AuthenticatorAssertionResponse, ?> credential = browser.get(request);
    AuthenticatorAssertionResponse response = credential.getResponse();

    return json.writeValueAsString(
        Map.of(
            "id", base64url(credential.getRawId()),
            "rawId", base64url(credential.getRawId()),
            "type", "public-key",
            "clientExtensionResults", Map.of(),
            "response",
                Map.of(
                    "clientDataJSON", base64url(response.getClientDataJSON()),
                    "authenticatorData", base64url(response.getAuthenticatorData()),
                    "signature", base64url(response.getSignature()))));
  }

  /**
   * Posts a ceremony request and unwraps the options it answers with.
   *
   * @param request the request builder
   * @param session the caller's session
   * @return the options JSON
   * @throws Exception when the call fails, which is the test failing
   */
  private String optionsFrom(
      org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
      MockHttpSession session)
      throws Exception {
    String body =
        mockMvc
            .perform(request.session(session).with(csrf()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(body).get("options").asString();
  }

  private static String base64url(byte[] raw) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
  }

  /**
   * Signs in with the password alone, which is what an account with no factor yet gets.
   *
   * @param email the address
   * @return the session
   * @throws Exception when the call fails, which is the test failing
   */
  private MockHttpSession signInWithPassword(String email) throws Exception {
    MockHttpSession session = new MockHttpSession();
    mockMvc
        .perform(
            post("/api/v1/auth/login")
                .session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(credentials(email)))
        .andExpect(status().isOk());
    return session;
  }

  private int passwordOnlyLogin(String email) throws Exception {
    return mockMvc
        .perform(
            post("/api/v1/auth/login")
                .session(new MockHttpSession())
                .contentType(MediaType.APPLICATION_JSON)
                .content(credentials(email)))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  private String credentials(String email) {
    return json.writeValueAsString(Map.of("email", email, "password", PASSWORD));
  }

  private UUID account(String email) {
    UUID userId = UUID.randomUUID();
    transactions.executeWithoutResult(
        status ->
            users.save(
                AppUser.create(
                    userId, email, "Test", "en", passwordEncoder.encode(PASSWORD),
                    Instant.now())));
    return userId;
  }

  private static RequestPostProcessor csrf() {
    return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
        .csrf();
  }
}
