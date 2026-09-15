/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * What a failed login does not say (REQ-SEC-110).
 *
 * <p>An address that has no account here and an address that has one, answered differently, is an
 * oracle: anybody can ask it, one address at a time, and learn who has an account on this instance.
 * That is the fact the answers have to hide, and hiding it is not the same as hiding the password —
 * a person who mistypes their own password and a stranger guessing at addresses have to be told the
 * same thing.
 *
 * <p>REQ-SEC-013 closes the other half, the timing channel: the comparison runs in constant time and
 * an unknown account still costs a dummy hash. Neither half is enough alone, which is why they are
 * two requirements and why this one is about the <em>content</em> of the answer.
 *
 * <p>The comparison here is of the whole document rather than of the status code. A status code is
 * the easy half to get right; the leak, when there is one, is in a {@code detail} that says "no such
 * account" for one and "wrong password" for the other. Only {@code traceId} is allowed to differ,
 * because it identifies the request rather than its outcome (REQ-NFR-042).
 *
 * <h2>Every attempt comes from its own address</h2>
 *
 * <p>Not decoration. The login throttle of REQ-SEC-012 counts per account <b>and per IP</b>, three
 * free attempts an hour, and it keeps that count in Valkey — which every test in this container
 * shares. A test that deliberately fails logins from the default {@code 127.0.0.1} spends a budget
 * that belongs to the whole suite, and the next test to sign in legitimately is answered {@code 429}
 * for a reason that has nothing to do with it. That is exactly what happened the first time this
 * class was written.
 *
 * <p>{@code AuthController} resolves the caller from {@code getRemoteAddr()} and deliberately
 * ignores {@code X-Forwarded-For} — a header anybody could set would let an attacker reset their own
 * throttle — so the address is set on the request itself, as a different client genuinely would.
 * The addresses come from {@code 203.0.113.0/24}, which RFC 5737 reserves for documentation.
 */
@DisplayName("A login that failed")
class EnumerationIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple-42";
  private static final String LOGIN = "/api/v1/auth/login";

  @Autowired private AppUserRepository users;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private ObjectMapper json;

  @Test
  @DisplayName("says the same thing about an address that has an account and one that has not")
  void theAnswerNamesNoAccount() throws Exception {
    String known = "enumeration-known@example.org";
    anAccount(known);

    ObjectNode unknownAddress =
        failedLogin("enumeration-nobody@example.org", "whatever-2026", "203.0.113.11");
    ObjectNode wrongPassword = failedLogin(known, "not-the-right-one-2026", "203.0.113.12");

    // The whole document, field for field. A status code is the easy half; the
    // leak lives in a `detail` that distinguishes the two.
    assertThat(unknownAddress)
        .as("an unknown address and a wrong password are one answer")
        .isEqualTo(wrongPassword);

    // And the answer is the registered condition rather than something ad hoc,
    // so a client branches on a token that is in `problem-types.yaml`.
    assertThat(unknownAddress.get("type").asString())
        .isEqualTo("https://home-inv.example/problems/unauthenticated");

    // Nothing in it names the address, the account or which half failed. Checked
    // rather than assumed: the detail is prose, and prose is what drifts.
    String text = unknownAddress.toString().toLowerCase(java.util.Locale.ROOT);
    assertThat(text)
        .as("the document says nothing about which half of the credential was wrong")
        .doesNotContain("unknown")
        .doesNotContain("no such")
        .doesNotContain("does not exist")
        .doesNotContain("enumeration-known");
  }

  @Test
  @DisplayName("says it again the same way, so a repeat is not a second signal")
  void theSecondAttemptSaysTheSame() throws Exception {
    String known = "enumeration-repeat@example.org";
    anAccount(known);

    // Twice each, alternating. A difference that only appears on the second
    // attempt — a counter that exists for a known account and not for an unknown
    // one — would be the same oracle one step further back.
    String nobody = "enumeration-nobody-2@example.org";
    ObjectNode firstUnknown = failedLogin(nobody, "whatever-2026", "203.0.113.21");
    ObjectNode firstKnown = failedLogin(known, "not-the-right-one-2026", "203.0.113.22");
    ObjectNode secondUnknown = failedLogin(nobody, "whatever-2026", "203.0.113.23");
    ObjectNode secondKnown = failedLogin(known, "not-the-right-one-2026", "203.0.113.24");

    assertThat(secondUnknown).isEqualTo(firstUnknown);
    assertThat(secondKnown).isEqualTo(firstKnown);
    assertThat(secondUnknown).isEqualTo(secondKnown);
  }

  // -------------------------------------------------------------------------

  /**
   * The problem document a refused login answered, with {@code traceId} removed.
   *
   * @param email what was presented as the address
   * @param password what was presented as the password
   * @param clientIp the address the attempt comes from, distinct per attempt so the suite's shared
   *     per-IP throttle budget is not spent here
   * @return the document, ready to be compared with another
   * @throws Exception when the request itself fails, which is the test failing
   */
  private ObjectNode failedLogin(String email, String password, String clientIp) throws Exception {
    String body =
        mockMvc
            .perform(
                post(LOGIN)
                    .with(
                        request -> {
                          request.setRemoteAddr(clientIp);
                          return request;
                        })
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            java.util.Map.of("email", email, "password", password))))
            .andExpect(status().isUnauthorized())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);

    JsonNode parsed = json.readTree(body);
    assertThat(parsed.isObject()).as("a refused login answers a problem document").isTrue();
    ObjectNode document = (ObjectNode) parsed;
    // The one field that may differ: it identifies the request, not its outcome.
    document.remove("traceId");
    return document;
  }

  /**
   * An account with a password and no second factor.
   *
   * <p>No factor is enrolled on purpose: this test never signs in successfully, and an account
   * without one keeps the setup to the thing under test.
   *
   * @param email the address it answers to
   */
  private void anAccount(String email) {
    transactions.executeWithoutResult(
        status ->
            users.save(
                AppUser.create(
                    UUID.randomUUID(),
                    email,
                    "Somebody",
                    "en",
                    passwordEncoder.encode(PASSWORD),
                    Instant.now())));
  }
}
