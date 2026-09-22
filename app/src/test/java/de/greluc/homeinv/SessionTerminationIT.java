/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.homeinv.identity.api.AccountAdministration;
import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/**
 * Signing every device out, by the account itself and by the operator (REQ-SEC-082).
 *
 * <h2>Why this builds its own MockMvc</h2>
 *
 * <p>The same reason {@code SessionOverviewIT} does, and it is the reason this is a class of its
 * own rather than two more tests in {@code ImmediateMeasuresIT}: every other test signs in by
 * passing a {@code MockHttpSession} from call to call, which never touches the session store. This
 * measure <b>is</b> the session store — it removes every entry an account has — so the store has to
 * be real, which means Spring Session's filter in the chain and cookies carried between requests,
 * exactly as a browser does.
 *
 * <h2>Why the accounts here have no second factor</h2>
 *
 * <p>So that a login is one call. The operator is deliberately a member of <b>no tenant</b>, which
 * is not a trick to dodge {@code REQ-AUTH-003}'s lock but the shape ADR-0066 describes: an instance
 * operator is not a user of any tenant, and the instance surface is exactly what such an account is
 * for.
 */
@DisplayName("Signing every device out")
class SessionTerminationIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple-42";

  @Autowired private AppUserRepository users;
  @Autowired private AccountAdministration accounts;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private ObjectMapper json;
  @Autowired private WebApplicationContext context;

  @BeforeEach
  void withTheSessionStore() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(
                context.getBean(de.greluc.homeinv.rest.TraceIdFilter.class),
                context.getBean(SessionRepositoryFilter.class))
            .apply(springSecurity())
            .build();
  }

  @Test
  @DisplayName("takes the caller's own session with it, which is how they can see it worked")
  void anAccountSignsAllOfItsDevicesOut() throws Exception {
    account("terminate-self@example.org", true);
    Cookie[] phone = signIn("terminate-self@example.org");
    Cookie[] laptop = signIn("terminate-self@example.org");

    mockMvc
        .perform(get("/api/v1/me/sessions").cookie(laptop))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2));

    mockMvc
        .perform(delete("/api/v1/me/sessions").cookie(laptop).with(csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ended").value(2));

    // Both, including the one that asked. Leaving the caller's alive would mean
    // the person taking the measure has to guess which of the listed sessions is
    // theirs, and the browser signing itself out is the visible proof.
    mockMvc.perform(get("/api/v1/me/sessions").cookie(laptop)).andExpect(status().isUnauthorized());
    mockMvc.perform(get("/api/v1/me/sessions").cookie(phone)).andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("is the operator's to take for somebody else, and does not lock them out")
  void anOperatorSignsAnAccountOut() throws Exception {
    UUID person = account("terminate-victim@example.org", true);
    UUID operator = account("terminate-operator@example.org", false);
    transactions.executeWithoutResult(
        status -> accounts.replaceEntitlements(operator, true, false, null, operator));

    Cookie[] theirs = signIn("terminate-victim@example.org");
    Cookie[] operatorSession = signIn("terminate-operator@example.org");

    mockMvc
        .perform(
            delete("/api/v1/instance/accounts/" + person + "/sessions")
                .cookie(operatorSession)
                .with(csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accountId").value(person.toString()))
        .andExpect(jsonPath("$.ended").value(1));

    mockMvc.perform(get("/api/v1/me/sessions").cookie(theirs)).andExpect(status().isUnauthorized());
    // The operator's own session is untouched: this measure names an account and
    // reaches that account only.
    mockMvc
        .perform(get("/api/v1/me/sessions").cookie(operatorSession))
        .andExpect(status().isOk());
    // Signed out and NOT locked out. The password is still good, which is
    // correct: locking is a different measure with different consequences, and
    // an operator who wants it clears the entitlements or suspends the tenant.
    assertThat(signIn("terminate-victim@example.org")).isNotEmpty();
  }

  @Test
  @DisplayName("is refused for an account nobody has heard of")
  void anAccountThatIsNotThere() throws Exception {
    UUID operator = account("terminate-unknown-operator@example.org", false);
    transactions.executeWithoutResult(
        status -> accounts.replaceEntitlements(operator, true, false, null, operator));
    Cookie[] session = signIn("terminate-unknown-operator@example.org");

    mockMvc
        .perform(
            delete("/api/v1/instance/accounts/" + UUID.randomUUID() + "/sessions")
                .cookie(session)
                .with(csrf()))
        .andExpect(status().isNotFound());
  }

  /**
   * An account with no second factor, so a login is one call.
   *
   * @param email the address
   * @param withTenant whether to provision a tenant it owns
   * @return the account's id
   */
  private UUID account(String email, boolean withTenant) {
    UUID userId = UUID.randomUUID();
    transactions.executeWithoutResult(
        status ->
            users.save(
                AppUser.create(
                    userId, email, "Test", "en", passwordEncoder.encode(PASSWORD), Instant.now())));
    if (withTenant) {
      provisioning.provision("Tenant of " + email, userId);
    }
    return userId;
  }

  /**
   * Signs in and returns what the browser would now hold.
   *
   * @param email the address
   * @return the cookies
   * @throws Exception when the login fails, which is the test failing
   */
  private Cookie[] signIn(String email) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
            .andExpect(status().isOk())
            .andReturn();
    return result.getResponse().getCookies();
  }

  private static org.springframework.test.web.servlet.request.RequestPostProcessor csrf() {
    return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
        .csrf();
  }
}
