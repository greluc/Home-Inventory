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

import de.greluc.homeinv.identity.application.TotpCodes;
import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.identity.infrastructure.CredentialKey;
import de.greluc.homeinv.identity.infrastructure.CredentialRepository;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.time.Instant;
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
import tools.jackson.databind.ObjectMapper;

/**
 * The second factor: enrolling one, signing in with it, and recovering from a lost phone
 * (REQ-AUTH-002, 12 §12.4).
 *
 * <p>The codes are generated here the way an authenticator app generates them — from the secret the
 * enrolment handed out, with {@link TotpCodes} — so what is under test is the whole loop rather than
 * one side of it agreeing with itself.
 */
@DisplayName("The second factor")
class SecondFactorIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple-42";

  @Autowired private AppUserRepository users;
  @Autowired private CredentialRepository credentials;
  @Autowired private CredentialKey credentialKey;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private ObjectMapper json;

  @Test
  @DisplayName("is enrolled in two steps, and the login then takes two as well")
  void enrolThenSignIn() throws Exception {
    UUID userId = account("mfa-enrol@example.org");
    MockHttpSession session = login("mfa-enrol@example.org");

    // Nothing yet.
    mockMvc
        .perform(get("/api/v1/auth/mfa/enrolment").session(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totpConfirmed").value(false))
        .andExpect(jsonPath("$.recoveryCodesLeft").value(0));

    String secret = beginEnrolment(session);

    // Unconfirmed, so the login is unaffected: somebody who scanned a QR code and
    // closed the app is not locked out of their own account.
    mockMvc
        .perform(get("/api/v1/auth/mfa/enrolment").session(session))
        .andExpect(jsonPath("$.totpConfirmed").value(false));
    assertThat(loginResponse("mfa-enrol@example.org")).isEqualTo(200);

    List<String> recoveryCodes = confirmEnrolment(session, secret);
    assertThat(recoveryCodes).hasSize(10);

    mockMvc
        .perform(get("/api/v1/auth/mfa/enrolment").session(session))
        .andExpect(jsonPath("$.totpConfirmed").value(true))
        .andExpect(jsonPath("$.recoveryCodesLeft").value(10));

    // From here the password alone is half a login.
    MockHttpSession pending = new MockHttpSession();
    mockMvc
        .perform(
            post("/api/v1/auth/login")
                .session(pending)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(credentials("mfa-enrol@example.org")))
        .andExpect(status().isUnauthorized())
        .andExpect(
            jsonPath("$.type").value("https://home-inv.example/problems/second-factor-required"));

    // A wrong code spends the pending login rather than allowing another guess.
    mockMvc
        .perform(
            post("/api/v1/auth/mfa")
                .session(pending)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("code", "000000"))))
        .andExpect(status().isUnauthorized());

    MockHttpSession second = new MockHttpSession();
    mockMvc
        .perform(
            post("/api/v1/auth/login")
                .session(second)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(credentials("mfa-enrol@example.org")))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            post("/api/v1/auth/mfa")
                .session(second)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("code", nextCodeFor(secret)))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.userId").value(userId.toString()));
  }

  @Test
  @DisplayName("takes a recovery code once, and only once")
  void recoveryCodeIsSingleUse() throws Exception {
    account("mfa-recovery@example.org");
    MockHttpSession session = login("mfa-recovery@example.org");
    String secret = beginEnrolment(session);
    List<String> recoveryCodes = confirmEnrolment(session, secret);
    String code = recoveryCodes.getFirst();

    assertThat(completeWith("mfa-recovery@example.org", code)).isEqualTo(200);

    // Spent. The answer is the same one a code that never existed gets.
    assertThat(completeWith("mfa-recovery@example.org", code)).isEqualTo(401);
    assertThat(completeWith("mfa-recovery@example.org", "AAAAA-AAAAA")).isEqualTo(401);

    // The rest of the set still works, and the count says how much is left.
    mockMvc
        .perform(
            get("/api/v1/auth/mfa/enrolment")
                .session(login("mfa-recovery@example.org", recoveryCodes.get(1))))
        .andExpect(jsonPath("$.recoveryCodesLeft").value(8));
  }

  @Test
  @DisplayName("refuses a code from a time step it has already accepted")
  void theSameCodeIsNotAcceptedTwice() throws Exception {
    account("mfa-replay@example.org");
    MockHttpSession session = login("mfa-replay@example.org");
    String secret = beginEnrolment(session);
    confirmEnrolment(session, secret);

    String code = nextCodeFor(secret);
    assertThat(completeWith("mfa-replay@example.org", code)).isEqualTo(200);

    // The same code is still arithmetically valid for the rest of its thirty
    // seconds. It is refused because the step is spent (RFC 6238 §5.2).
    assertThat(completeWith("mfa-replay@example.org", code)).isEqualTo(401);
  }

  @Test
  @DisplayName("is removed only with a code, and stores no secret anybody could read")
  void removalNeedsACodeAndTheSecretIsSealed() throws Exception {
    UUID userId = account("mfa-removal@example.org");
    MockHttpSession session = login("mfa-removal@example.org");
    String secret = beginEnrolment(session);
    confirmEnrolment(session, secret);

    // What is stored is not the secret. Reading the column and treating it as one
    // is exactly the attack the sealing exists for.
    String stored =
        transactions.execute(
            status -> credentials.findTotp(userId).orElseThrow().material());
    assertThat(stored).startsWith("v1:").doesNotContain(secret);
    assertThat(TotpCodes.base32(credentialKey.open(stored))).isEqualTo(secret);

    // An open session is not enough to take the factor off.
    mockMvc
        .perform(
            post("/api/v1/auth/mfa/totp/removal")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("code", "000000"))))
        .andExpect(status().isUnauthorized())
        .andExpect(
            jsonPath("$.type").value("https://home-inv.example/problems/second-factor-invalid"));

    // A second enrolment over a confirmed one is a conflict, not a replacement.
    mockMvc
        .perform(post("/api/v1/auth/mfa/totp").session(session).with(csrf()))
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.type").value("https://home-inv.example/problems/second-factor-enrolled"));

    mockMvc
        .perform(
            post("/api/v1/auth/mfa/totp/removal")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("code", nextCodeFor(secret)))))
        .andExpect(status().isNoContent());

    // And the login is one call again.
    assertThat(loginResponse("mfa-removal@example.org")).isEqualTo(200);
    mockMvc
        .perform(get("/api/v1/auth/mfa/enrolment").session(login("mfa-removal@example.org")))
        .andExpect(jsonPath("$.totpConfirmed").value(false))
        .andExpect(jsonPath("$.recoveryCodesLeft").value(0));
  }

  // -------------------------------------------------------------------------

  /**
   * Begins an enrolment and returns the secret it handed out.
   *
   * @param session the caller's session
   * @return the base32 secret
   * @throws Exception when the call fails, which is the test failing
   */
  private String beginEnrolment(MockHttpSession session) throws Exception {
    String body =
        mockMvc
            .perform(post("/api/v1/auth/mfa/totp").session(session).with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.provisioningUri").value(org.hamcrest.Matchers
                .startsWith("otpauth://totp/")))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(body).get("secret").asString();
  }

  /**
   * Confirms an enrolment with a code generated from the secret.
   *
   * @param session the caller's session
   * @param secret the base32 secret
   * @return the recovery codes
   * @throws Exception when the call fails, which is the test failing
   */
  private List<String> confirmEnrolment(MockHttpSession session, String secret) throws Exception {
    String body =
        mockMvc
            .perform(
                post("/api/v1/auth/mfa/totp/confirmation")
                    .session(session)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("code", codeFor(secret)))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readValue(json.readTree(body).get("codes").toString(),
        json.getTypeFactory().constructCollectionType(List.class, String.class));
  }

  /**
   * A password-only login, for asking what it answers.
   *
   * @param email the address
   * @return the status
   * @throws Exception when the call fails, which is the test failing
   */
  private int loginResponse(String email) throws Exception {
    return mockMvc
        .perform(
            post("/api/v1/auth/login")
                .session(new MockHttpSession())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(credentials(email)))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  /**
   * A whole two-step login, and what the second step answered.
   *
   * @param email the address
   * @param code the second factor
   * @return the status of the second call
   * @throws Exception when a call fails, which is the test failing
   */
  private int completeWith(String email, String code) throws Exception {
    MockHttpSession session = new MockHttpSession();
    mockMvc
        .perform(
            post("/api/v1/auth/login")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(credentials(email)))
        .andExpect(status().isUnauthorized());
    return mockMvc
        .perform(
            post("/api/v1/auth/mfa")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("code", code))))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  /**
   * Signs in with the second factor and returns the session.
   *
   * @param email the address
   * @param code the second factor
   * @return the established session
   * @throws Exception when a call fails, which is the test failing
   */
  private MockHttpSession login(String email, String code) throws Exception {
    MockHttpSession session = new MockHttpSession();
    mockMvc
        .perform(
            post("/api/v1/auth/login")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(credentials(email)))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            post("/api/v1/auth/mfa")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("code", code))))
        .andExpect(status().isOk());
    return session;
  }

  /**
   * The code an authenticator app would be showing right now.
   *
   * @param secret the base32 secret the enrolment handed out
   * @return six digits
   */
  private String codeFor(String secret) {
    return TotpCodes.generate(decode(secret), TotpCodes.stepOf(Instant.now()));
  }

  /**
   * The code the app will show next, which is what a person uses after the current one is spent.
   *
   * <p>Accepted because the window reaches one step forward, and needed here because a test runs
   * inside one thirty-second step: waiting for the clock would put half a minute into every case
   * that signs in twice.
   *
   * @param secret the base32 secret the enrolment handed out
   * @return six digits
   */
  private String nextCodeFor(String secret) {
    return TotpCodes.generate(decode(secret), TotpCodes.stepOf(Instant.now()) + 1);
  }

  /**
   * Base32 back to bytes, which only a test needs.
   *
   * @param base32 the secret as the enrolment returned it
   * @return the raw secret
   */
  private static byte[] decode(String base32) {
    String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    int buffer = 0;
    int bits = 0;
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    for (char c : base32.toCharArray()) {
      buffer = (buffer << 5) | alphabet.indexOf(c);
      bits += 5;
      if (bits >= 8) {
        out.write((buffer >> (bits - 8)) & 0xff);
        bits -= 8;
      }
    }
    return out.toByteArray();
  }

  /**
   * Signs in with a password alone.
   *
   * @param email the address
   * @return the established session
   * @throws Exception when the call fails, which is the test failing
   */
  private MockHttpSession login(String email) throws Exception {
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

  private static org.springframework.test.web.servlet.request.RequestPostProcessor csrf() {
    return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
        .csrf();
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
    provisioning.provision("Tenant of " + email, userId);
    return userId;
  }
}
