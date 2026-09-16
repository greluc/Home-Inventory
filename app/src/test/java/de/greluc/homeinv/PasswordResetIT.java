/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.notification.api.SecurityNotifications;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/**
 * Getting back into an account whose password is gone (REQ-SEC-018).
 *
 * <p>The requirement names four properties and every one of them is a way the flow can be wrong
 * rather than merely incomplete: single use, thirty minutes, every session ended, and the **old**
 * address told. A reset that quietly kept the intruder's session open would look like it worked.
 *
 * <p>The fifth property is the one that is easiest to lose and hardest to notice: asking tells the
 * asker nothing. An address with an account and one without get the same answer, so the endpoint
 * cannot be used to ask who is registered on this instance (REQ-SEC-110).
 */
@DisplayName("Resetting a password")
class PasswordResetIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple-42";

  /**
   * What a reset changes the password to.
   *
   * <p>A variant of the one passphrase rather than a second invented one. {@code .gitleaks.toml}
   * allowlists exactly one string for the test sources and says why: fifteen classes had each made
   * up their own until the entropy check fired on one, and a bespoke password that happens not to
   * trip today is how the next false positive arrives. This test needs two <em>different</em>
   * passwords — that is its subject — and derives the second rather than inventing it.
   */
  private static final String NEW_PASSWORD = PASSWORD + "-after-the-reset";
  private static final String RESET = "/api/v1/auth/password-reset";
  private static final String COMPLETE = RESET + "/complete";

  /** The link the message carries, which is the only place the token ever appears. */
  private static final Pattern TOKEN_IN_MESSAGE =
      Pattern.compile("/reset-password\\?token=([A-Za-z0-9_-]+)");

  @Autowired private AppUserRepository users;
  @Autowired private SecurityNotifications notifications;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private JdbcClient jdbc;
  @Autowired private ObjectMapper json;
  @Autowired private WebApplicationContext context;

  /**
   * Puts the real session store in the chain.
   *
   * <p>The headline property of a reset is that <b>every session ends</b>, and a {@code
   * MockHttpSession} passed from call to call never touches the store that holds them. So this
   * class signs in with cookies exactly as a browser does, which is the only way the assertion
   * means what it says (the same reason {@code SessionOverviewIT} does it).
   */
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
  @DisplayName("sends a link, takes the new password, and ends every session that was open")
  void theWholeFlow() throws Exception {
    UUID userId = anAccount("whole-flow");
    String email = emailOf("whole-flow");
    Cookie[] before = signInWith(email, PASSWORD);

    // The session works right now, which is what makes the assertion after the
    // reset mean something.
    mockMvc.perform(get("/api/v1/auth/me").cookie(before)).andExpect(status().isOk());

    ask(email).andExpect(status().isNoContent());

    SecurityNotifications.Queued queued = onlyNotification(userId, "security.password-reset");
    assertThat(queued.address()).isEqualTo(email);
    assertThat(queued.state()).isEqualTo("QUEUED");

    complete(tokenFor(userId), NEW_PASSWORD).andExpect(status().isNoContent());

    // Every session the account had open is gone. Somebody who took the account
    // over is signed out by the real owner's reset -- without this the reset
    // would return the password and leave the intruder logged in.
    mockMvc.perform(get("/api/v1/auth/me").cookie(before)).andExpect(status().isUnauthorized());

    // And the new password is the password.
    Cookie[] after = signInWith(email, NEW_PASSWORD);
    mockMvc.perform(get("/api/v1/auth/me").cookie(after)).andExpect(status().isOk());

    // The old address is told, at the address the account had. That is the half
    // that matters: if this was not the owner, they find out somewhere the
    // attacker does not control.
    SecurityNotifications.Queued told = onlyNotification(userId, "security.password-changed");
    assertThat(told.address()).isEqualTo(email);
  }

  @Test
  @DisplayName("spends the token, so a forwarded message opens nothing")
  void singleUse() throws Exception {
    UUID userId = anAccount("single-use");
    ask(emailOf("single-use")).andExpect(status().isNoContent());
    String token = tokenFor(userId);

    complete(token, NEW_PASSWORD).andExpect(status().isNoContent());
    complete(token, PASSWORD + "-a-third-time").andExpect(status().isUnprocessableContent());

    // And the second attempt changed nothing: the password is still the one the
    // first redemption set.
    signInWith(emailOf("single-use"), NEW_PASSWORD);
  }

  @Test
  @DisplayName("refuses a token that has run out of time")
  void thirtyMinutes() throws Exception {
    UUID userId = anAccount("expiry");
    ask(emailOf("expiry")).andExpect(status().isNoContent());
    String token = tokenFor(userId);

    // Written directly rather than waiting half an hour. What is being tested is
    // the lookup: an expired row must not be found, and "expired" is a property
    // of the row rather than of the caller.
    // Both timestamps move, not just the expiry: the row checks that it expires
    // after it was requested, and a reset that expired before it was asked for
    // is not a state the flow can reach. This is the state it can: asked for an
    // hour ago, good for thirty minutes.
    transactions.executeWithoutResult(
        status ->
            jdbc.sql(
                    """
                    update identity.password_reset
                       set requested_at = ?, expires_at = ?
                     where user_id = ?
                    """)
                .param(java.sql.Timestamp.from(Instant.now().minus(61, ChronoUnit.MINUTES)))
                .param(java.sql.Timestamp.from(Instant.now().minus(31, ChronoUnit.MINUTES)))
                .param(userId)
                .update());

    complete(token, NEW_PASSWORD).andExpect(status().isUnprocessableContent());
    // The old password still works, because nothing happened.
    signInWith(emailOf("expiry"), PASSWORD);
  }

  @Test
  @DisplayName("replaces an open reset rather than opening a second one")
  void oneOpenResetPerAccount() throws Exception {
    UUID userId = anAccount("replace");
    ask(emailOf("replace")).andExpect(status().isNoContent());
    String first = tokenFor(userId);

    ask(emailOf("replace")).andExpect(status().isNoContent());
    String second = tokenFor(userId);
    assertThat(second).isNotEqualTo(first);

    // Two live tokens would be two ways in. Somebody clicking the older message
    // is told it no longer works rather than let in.
    complete(first, NEW_PASSWORD).andExpect(status().isUnprocessableContent());
    complete(second, NEW_PASSWORD).andExpect(status().isNoContent());
  }

  @Test
  @DisplayName("answers an address with no account exactly as it answers one with")
  void tellsTheAskerNothing() throws Exception {
    anAccount("known");

    ask(emailOf("known")).andExpect(status().isNoContent());
    ask("password-reset-nobody-at-all@example.org").andExpect(status().isNoContent());

    // Nothing was queued for the address nobody has, which is the other half:
    // the answer is identical and so is the absence of a message.
    assertThat(
            jdbc.sql(
                    "select count(*) from notification.security_notification where address = ?")
                .param("password-reset-nobody-at-all@example.org")
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  @DisplayName("refuses a password under the policy, and does not spend the token doing so")
  void thePolicyApplies() throws Exception {
    UUID userId = anAccount("policy");
    ask(emailOf("policy")).andExpect(status().isNoContent());
    String token = tokenFor(userId);

    // REQ-SEC-011: twelve characters, no forced complexity. Eleven is refused.
    complete(token, "short-pass1").andExpect(status().isUnprocessableContent());

    // And the token survived the refusal. A policy failure that spent the link
    // would send somebody back to the login page to ask for another one.
    complete(token, NEW_PASSWORD).andExpect(status().isNoContent());
  }

  @Test
  @DisplayName("throttles a run of requests, so the endpoint cannot fill somebody's mailbox")
  void theThrottleEngages() throws Exception {
    anAccount("throttle");
    String email = emailOf("throttle");
    String from = "198.51.100.250";

    // Three are free, as on the login path. The fourth is where the wait starts.
    for (int attempt = 0; attempt < 4; attempt++) {
      askFrom(email, from).andExpect(status().isNoContent());
    }
    askFrom(email, from).andExpect(status().isTooManyRequests());

    // And it is the reset's own counter: signing in still works, because a flood
    // of reset requests must not lock the account holder out.
    signInWith(email, PASSWORD);
  }

  // -------------------------------------------------------------------------

  /**
   * Asks for a reset, from a caller address of this test's own.
   *
   * <p>The address matters. The throttle counts per account <b>and</b> per calling address, and
   * every test here would otherwise be the same caller — so the third test in the class would be
   * throttled for what the first two did. Distinct callers have distinct addresses in the world
   * this endpoint lives in; giving each test one is what makes the test resemble it.
   *
   * @param email the address a reset is asked for
   * @return the result, to assert on
   * @throws Exception when the request cannot be performed
   */
  private org.springframework.test.web.servlet.ResultActions ask(String email) throws Exception {
    return askFrom(email, addressFor(email));
  }

  private org.springframework.test.web.servlet.ResultActions askFrom(String email, String clientIp)
      throws Exception {
    return mockMvc.perform(
        post(RESET)
            .with(csrf())
            .with(
                request -> {
                  request.setRemoteAddr(clientIp);
                  return request;
                })
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("email", email))));
  }

  /**
   * A stable, distinct caller address per test address.
   *
   * @param email the address being asked about
   * @return an address in the documentation range, so it can never be a real one
   */
  private static String addressFor(String email) {
    return "198.51.100." + (Math.abs(email.hashCode()) % 200 + 1);
  }

  private org.springframework.test.web.servlet.ResultActions complete(String token, String password)
      throws Exception {
    return mockMvc.perform(
        post(COMPLETE)
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("token", token, "password", password))));
  }

  /**
   * The token out of the message that was queued for this account.
   *
   * <p>Read from the message body on purpose. The token exists in exactly one place — the message —
   * and a test that took it from the database would prove the row rather than the link somebody
   * actually clicks.
   *
   * @param userId whose account
   * @return the token
   */
  private String tokenFor(UUID userId) {
    String body =
        jdbc
            .sql(
                """
                select body_text from notification.security_notification
                where user_id = ? and kind = 'security.password-reset'
                order by created_at desc limit 1
                """)
            .param(userId)
            .query(String.class)
            .single();
    Matcher link = TOKEN_IN_MESSAGE.matcher(body);
    assertThat(link.find()).as("the message carries a reset link").isTrue();
    return link.group(1);
  }

  private SecurityNotifications.Queued onlyNotification(UUID userId, String kind) {
    List<SecurityNotifications.Queued> all = notifications.of(userId, 20);
    Optional<SecurityNotifications.Queued> match =
        all.stream().filter(queued -> queued.kind().equals(kind)).findFirst();
    assertThat(match).as("a %s notification for %s", kind, userId).isPresent();
    return match.orElseThrow();
  }

  /**
   * Signs in and returns the cookies, which is what a session is here.
   *
   * @param email the address
   * @param password the password that should work
   * @return the session cookies
   * @throws Exception when signing in fails, which is itself the assertion in most callers
   */
  private Cookie[] signInWith(String email, String password) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("email", email, "password", password))))
            .andExpect(status().isOk())
            .andReturn();
    return result.getResponse().getCookies();
  }

  private static String emailOf(String name) {
    return "password-reset-" + name + "@example.org";
  }

  private UUID anAccount(String name) {
    UUID userId = UUID.randomUUID();
    String email = emailOf(name);
    transactions.executeWithoutResult(
        status ->
            users.save(
                AppUser.create(
                    userId, email, name, "en", passwordEncoder.encode(PASSWORD), Instant.now())));
    // A tenant of their own, so that signing in lands somewhere. The reset
    // itself needs none, which is the point of the table being instance-wide.
    provisioning.provision("Password reset " + name, userId);
    return userId;
  }
}
