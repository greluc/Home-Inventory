/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.plugins.application.DefaultPluginRegistry;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What an {@code open} instance does with an identity nobody has linked (REQ-AUTH-004).
 *
 * <p>The other three modes refuse, which {@link FederatedSignInIT} proves on the default one. Here
 * the instance creates accounts, and the three endings are the interesting ones:
 *
 * <ul>
 *   <li>a <b>verified</b> address nobody holds becomes an account, with no password;
 *   <li>a verified address that <b>already has one</b> is refused rather than linked — which is
 *       REQ-AUTH-006, and the single most important refusal in this feature;
 *   <li>an <b>unverified</b> address creates nothing, because it is a claim about somebody else.
 * </ul>
 *
 * <p>The provider is installed during the context's own refresh rather than in a {@code @BeforeEach}:
 * an {@code open} instance that can confirm no address at all <b>refuses to start</b>, and that
 * check runs when the application is ready. Installing it later would mean starting an instance the
 * requirement says must not start.
 */
@DisplayName("An open instance")
@TestPropertySource(properties = "homeinv.registration-mode=open")
@Import(FederatedRegistrationIT.InstallTheProvider.class)
class FederatedRegistrationIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple-42";

  @Autowired private AppUserRepository users;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;

  @AfterAll
  static void stopTheProvider() {
    TestIdentityProvider.stop();
  }

  @Test
  @DisplayName("creates an account for a verified address nobody holds")
  void aVerifiedAddressBecomesAnAccount() throws Exception {
    String email = "new-" + UUID.randomUUID() + "@example.org";
    TestIdentityProvider.willReport("subject-" + UUID.randomUUID(), email, true);

    mockMvc
        .perform(get("/api/v1/auth/federated/callback").param("state", beginAndGetState())
            .param("code", "c"))
        // A new account has no second factor, so the session is established at
        // once. One that enrols one later stops where a password login stops.
        .andExpect(status().isSeeOther())
        .andExpect(header().string("Location", "/"));

    assertThat(users.findByEmail(email)).isPresent();
  }

  @Test
  @DisplayName("refuses an address that already has an account, rather than linking it")
  void anAddressWithAnAccountIsNotLinked() throws Exception {
    String email = "taken-" + UUID.randomUUID() + "@example.org";
    UUID existing = anAccount(email);

    TestIdentityProvider.willReport("subject-" + UUID.randomUUID(), email, true);
    mockMvc
        .perform(get("/api/v1/auth/federated/callback").param("state", beginAndGetState())
            .param("code", "c"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("federated-address-taken")));

    // REQ-AUTH-006 in one assertion: the account is untouched and nothing was
    // linked to it. Somebody who controls a provider cannot walk into an account
    // by asserting its address.
    mockMvc
        .perform(get("/api/v1/auth/federated/links").session(signIn(email, PASSWORD)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
    assertThat(users.findById(existing)).isPresent();
  }

  @Test
  @DisplayName("creates nothing from an address the provider did not confirm")
  void anUnverifiedAddressCreatesNothing() throws Exception {
    String email = "unconfirmed-" + UUID.randomUUID() + "@example.org";
    TestIdentityProvider.willReport("subject-" + UUID.randomUUID(), email, false);

    mockMvc
        .perform(get("/api/v1/auth/federated/callback").param("state", beginAndGetState())
            .param("code", "c"))
        .andExpect(status().isForbidden())
        .andExpect(
            jsonPath("$.type")
                .value(org.hamcrest.Matchers.endsWith("federated-address-unverified")));

    assertThat(users.findByEmail(email)).isEmpty();
  }

  // -------------------------------------------------------------------------

  /** Starts a sign-in and returns the handle out of the authorization URL. */
  private String beginAndGetState() throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/auth/federated/begin")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
            .andExpect(status().isOk())
            .andReturn();
    String url =
        new ObjectMapper()
            .readTree(result.getResponse().getContentAsString())
            .get("authorizationUrl")
            .asText();
    for (String pair : URI.create(url).getQuery().split("&")) {
      String[] parts = pair.split("=", 2);
      if ("state".equals(parts[0])) {
        return parts[1];
      }
    }
    throw new AssertionError("the authorization URL carries no state: " + url);
  }

  /** An account with a password and a second factor, as every other test makes one. */
  private UUID anAccount(String email) {
    UUID userId = UUID.randomUUID();
    transactions.executeWithoutResult(
        status ->
            users.save(
                AppUser.create(
                    userId, email, "Ada", "en", passwordEncoder.encode(PASSWORD), Instant.now())));
    enrolSecondFactor(userId);
    return userId;
  }

  /**
   * Installs the provider while the context is still refreshing.
   *
   * <p>{@code OpenRegistrationReadiness} asks at {@link
   * org.springframework.boot.context.event.ApplicationReadyEvent} whether anything here can confirm
   * an address, and stops the instance when nothing can. That event is after this one, so the
   * provider is in place by the time it is asked — which is also the order a real deployment has
   * it in: the plugin is installed before the instance is switched to {@code open}.
   */
  @TestConfiguration(proxyBeanMethods = false)
  static class InstallTheProvider {

    /**
     * The listener that does it.
     *
     * @param registry the plugin registry
     * @return a listener that installs the provider once, during refresh
     */
    @Bean
    ApplicationListener<ContextRefreshedEvent> installTheIdentityProvider(
        DefaultPluginRegistry registry) {
      return event -> {
        try {
          TestIdentityProvider.install(registry, PKI);
        } catch (Exception failed) {
          throw new IllegalStateException("The test provider could not be installed", failed);
        }
      };
    }
  }
}
