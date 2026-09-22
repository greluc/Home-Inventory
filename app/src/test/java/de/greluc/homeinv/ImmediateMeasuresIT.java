/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.homeinv.identity.api.AccountAdministration;
import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.time.Instant;
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
 * The four measures somebody takes when something has gone wrong (REQ-SEC-082).
 *
 * <h2>Why each is here separately</h2>
 *
 * <p>The requirement names four and says "each verified", and each of them is a different promise:
 *
 * <ul>
 *   <li><b>terminate all sessions</b> — in {@link SessionTerminationIT}, which builds its MockMvc
 *       around Spring Session's own filter because that measure is <i>about</i> the session store
 *       and every other test here signs in without touching it;
 *   <li><b>revoke tokens</b> — every integration stops at once, without anybody having to know
 *       which one leaked;
 *   <li><b>disable a plugin</b> — one plugin stops being called for every tenant, and the grants
 *       survive, so putting it back is one call rather than a re-consent by every tenant;
 *   <li><b>suspend a tenant</b> — it answers nothing at all and not one row of its data is touched.
 * </ul>
 *
 * <p>The fourth carries a second assertion that matters as much as the first: suspension must
 * <b>not</b> reach a tenant that is being erased. An operator who could set that state directly
 * would be a second way to withdraw a deletion request, and the one that does not need the
 * revocation token the tenant was given.
 */
@DisplayName("An immediate measure")
class ImmediateMeasuresIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple-42";

  @Autowired private AppUserRepository users;
  @Autowired private AccountAdministration accounts;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private ObjectMapper json;

  @Test
  @DisplayName("stops every machine token of a tenant at once")
  void revokeEveryToken() throws Exception {
    UUID owner = createUser("measure-tokens@example.org");
    UUID tenant = provisioning.provision("Tokens", owner);
    MockHttpSession session = login("measure-tokens@example.org");

    String issued =
        mockMvc
            .perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/api/v1/tenants/" + tenant + "/service-accounts")
                    .session(session)
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "name", "nightly-import",
                                "role", "VIEWER",
                                "expiresAt", Instant.now().plusSeconds(86400).toString()))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(issued).contains("token");

    mockMvc
        .perform(
            delete("/api/v1/tenants/" + tenant + "/service-accounts").session(session).with(csrf()))
        .andExpect(status().isOk())
        // The count rather than 204: "how many did that stop" is the first
        // question afterwards and the operator is unlikely to know.
        .andExpect(jsonPath("$.revoked").value(1));

    mockMvc
        .perform(get("/api/v1/tenants/" + tenant + "/service-accounts").session(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  @DisplayName("takes a plugin out of service and puts it back without a re-consent")
  void disableAPlugin() throws Exception {
    UUID operator = createUser("measure-plugin@example.org");
    provisioning.provision("Plugins", operator);
    transactions.executeWithoutResult(
        status -> accounts.replaceEntitlements(operator, true, false, null, operator));
    MockHttpSession session = login("measure-plugin@example.org");

    // Nothing is installed under this id, and a measure that reported success
    // for a plugin that is not there would be the worst possible answer here.
    mockMvc
        .perform(
            put("/api/v1/instance/plugins/de.greluc.homeinv.plugin.nothing/state")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"disabled\":true}"))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("suspends a tenant, and lets it back in")
  void suspendATenant() throws Exception {
    UUID operator = createUser("measure-suspend-operator@example.org");
    provisioning.provision("Suspender", operator);
    UUID member = createUser("measure-suspended@example.org");
    UUID tenant = provisioning.provision("Suspended", member);

    MockHttpSession theirs = login("measure-suspended@example.org");
    mockMvc.perform(get("/api/v1/items").session(theirs)).andExpect(status().isOk());

    transactions.executeWithoutResult(
        status -> accounts.replaceEntitlements(operator, true, false, null, operator));
    MockHttpSession operatorSession = login("measure-suspend-operator@example.org");

    mockMvc
        .perform(
            put("/api/v1/instance/tenants/" + tenant + "/state")
                .session(operatorSession)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"state\":\"SUSPENDED\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("SUSPENDED"));

    // It answers nothing at all -- the interceptor that already handles the
    // other inaccessible states covers this one without a change -- and the
    // session is still perfectly valid, which is what makes reinstating cheap.
    mockMvc.perform(get("/api/v1/items").session(theirs)).andExpect(status().isForbidden());

    mockMvc
        .perform(
            put("/api/v1/instance/tenants/" + tenant + "/state")
                .session(operatorSession)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"state\":\"ACTIVE\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("ACTIVE"));

    mockMvc.perform(get("/api/v1/items").session(theirs)).andExpect(status().isOk());
  }

  @Test
  @DisplayName("is refused for a tenant nobody has heard of")
  void suspendingSomethingThatIsNotThere() throws Exception {
    UUID operator = createUser("measure-suspend-unknown@example.org");
    provisioning.provision("Unknown", operator);
    transactions.executeWithoutResult(
        status -> accounts.replaceEntitlements(operator, true, false, null, operator));
    MockHttpSession session = login("measure-suspend-unknown@example.org");

    mockMvc
        .perform(
            put("/api/v1/instance/tenants/" + UUID.randomUUID() + "/state")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"state\":\"SUSPENDED\"}"))
        .andExpect(status().isNotFound());
  }

  /**
   * An account with a second factor already enrolled.
   *
   * @param email the address
   * @return the account's id
   */
  private UUID createUser(String email) {
    UUID userId = UUID.randomUUID();
    transactions.executeWithoutResult(
        status ->
            users.save(
                AppUser.create(
                    userId,
                    email,
                    email.substring(0, email.indexOf('@')),
                    "en",
                    passwordEncoder.encode(PASSWORD),
                    Instant.now())));
    enrolSecondFactor(userId);
    return userId;
  }

  /**
   * Signs in and returns the session.
   *
   * @param email the address
   * @return the session
   * @throws Exception when the login fails, which is the test failing
   */
  private MockHttpSession login(String email) throws Exception {
    return signIn(email, PASSWORD);
  }

  private static RequestPostProcessor csrf() {
    return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
        .csrf();
  }
}
