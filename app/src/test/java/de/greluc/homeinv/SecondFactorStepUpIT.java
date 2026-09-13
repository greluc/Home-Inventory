/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.homeinv.identity.application.TotpCodes;
import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.rest.SessionEstablisher;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Critical operations ask for the second factor again (REQ-AUTH-011, 12 §12.4).
 *
 * <p>Fifteen minutes, and the test does not wait for them: it moves the session's record of when
 * the factor was proved, which is the one thing the window is measured against. What is under test
 * is the rule, not the clock.
 *
 * <p>The two halves behave differently on purpose. An operation is <b>refused</b> —
 * {@code second-factor-stale}, and the client posts a code and repeats it. A sensitive field is
 * <b>removed</b>, and the answer is still {@code 200}: a list with one sensitive column in it would
 * otherwise become unreadable, and a refusal somebody meets while scrolling is one they learn to
 * click past.
 */
@DisplayName("The re-confirmation")
class SecondFactorStepUpIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple-42";

  @Autowired private AppUserRepository users;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private JdbcClient jdbc;
  @Autowired private ObjectMapper json;

  @Test
  @DisplayName("is asked for again after fifteen minutes, and a code gives it back")
  void aStaleProofIsRefusedUntilACodeIsGiven() throws Exception {
    UUID owner = account("stepup-owner@example.org");
    UUID tenantId = provisioning.provision("Step up", owner);
    String secret = secondFactorSecrets(owner);
    UUID member = memberOf(tenantId, "stepup-member@example.org");
    MockHttpSession session = signIn("stepup-owner@example.org", PASSWORD);

    // Freshly signed in: the factor was proved a moment ago.
    mockMvc
        .perform(
            put("/api/v1/tenants/" + tenantId + "/members/" + member)
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("role", "ADMIN"))))
        .andExpect(status().isOk());

    goStale(session);

    // Granting a role is one of the operations 12 §12.4 names.
    mockMvc
        .perform(
            put("/api/v1/tenants/" + tenantId + "/members/" + member)
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("role", "MEMBER"))))
        .andExpect(status().isForbidden())
        .andExpect(
            jsonPath("$.type").value("https://home-inv.example/problems/second-factor-stale"));

    // Reading is untouched: the re-confirmation guards what changes something.
    mockMvc
        .perform(get("/api/v1/tenants/" + tenantId + "/members").session(session))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            post("/api/v1/auth/mfa/step-up")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("code", nextCodeFor(secret)))))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(
            put("/api/v1/tenants/" + tenantId + "/members/" + member)
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("role", "MEMBER"))))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("takes a wrong code without refreshing anything")
  void aWrongCodeChangesNothing() throws Exception {
    UUID owner = account("stepup-wrong@example.org");
    UUID tenantId = provisioning.provision("Wrong code", owner);
    UUID member = memberOf(tenantId, "stepup-wrong-member@example.org");
    MockHttpSession session = signIn("stepup-wrong@example.org", PASSWORD);
    goStale(session);

    mockMvc
        .perform(
            post("/api/v1/auth/mfa/step-up")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("code", "000000"))))
        .andExpect(status().isUnauthorized())
        .andExpect(
            jsonPath("$.type").value("https://home-inv.example/problems/second-factor-invalid"));

    mockMvc
        .perform(
            put("/api/v1/tenants/" + tenantId + "/members/" + member)
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("role", "ADMIN"))))
        .andExpect(status().isForbidden())
        .andExpect(
            jsonPath("$.type").value("https://home-inv.example/problems/second-factor-stale"));
  }

  // -------------------------------------------------------------------------

  /**
   * Moves the session's proof out of the window.
   *
   * <p>Sixteen minutes back, one past {@code SecondFactorPolicy.RECONFIRMATION_WINDOW}. The
   * attribute is the one the session carries; waiting a quarter of an hour would be the alternative.
   *
   * @param session the signed-in session
   */
  private void goStale(MockHttpSession session) {
    session.setAttribute(
        SessionEstablisher.SECOND_FACTOR_AT,
        Instant.now().minusSeconds(16 * 60).getEpochSecond());
  }

  /**
   * Adds somebody to the tenant, so there is a role to grant.
   *
   * @param tenantId the tenant
   * @param email the member's address
   * @return their account
   */
  private UUID memberOf(UUID tenantId, String email) {
    UUID userId = account(email);
    TenantContext.runAs(
        tenantId,
        () ->
            transactions.executeWithoutResult(
                status ->
                    jdbc.sql(
                            "insert into tenancy.membership (id, tenant_id, user_id, role,"
                                + " created_at, updated_at, version)"
                                + " values (?, ?, ?, 'MEMBER', now(), now(), 1)")
                        .params(UUID.randomUUID(), tenantId, userId)
                        .update()));
    return userId;
  }

  /**
   * The code the authenticator app will show next.
   *
   * <p>Next rather than current: signing in spent the current step, and a code from a step already
   * accepted is refused (RFC 6238 §5.2). A person waits thirty seconds; a test asks for the one
   * after.
   *
   * @param secret the base32 secret
   * @return six digits
   */
  private String nextCodeFor(String secret) {
    return TotpCodes.generate(decode(secret), TotpCodes.stepOf(Instant.now()) + 1);
  }

  private static byte[] decode(String base32) {
    String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int buffer = 0;
    int bits = 0;
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

  private UUID account(String email) {
    UUID userId = UUID.randomUUID();
    transactions.executeWithoutResult(
        status ->
            users.save(
                AppUser.create(
                    userId, email, "Test", "en", passwordEncoder.encode(PASSWORD),
                    Instant.now())));
    secrets.put(userId, enrolSecondFactor(userId));
    return userId;
  }

  /** The secrets this test enrolled, so a step-up can be answered. */
  private final java.util.Map<UUID, String> secrets = new java.util.HashMap<>();

  private String secondFactorSecrets(UUID userId) {
    return secrets.get(userId);
  }

  private static RequestPostProcessor csrf() {
    return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
        .csrf();
  }
}
