/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.greluc.homeinv.identity.domain.AppUser;
import de.greluc.homeinv.identity.infrastructure.AppUserRepository;
import de.greluc.homeinv.tenancy.application.TenantProvisioningService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * No password, token or key reaches the log (REQ-SEC-050).
 *
 * <h2>Why known values and not a pattern</h2>
 *
 * <p>A regular expression for "looks like a secret" finds the secrets somebody thought of. This test
 * takes the values it put in — a password it just authenticated with, the signing key the running
 * context loaded, the session id the container issued — and searches every line the application
 * produced for them. A leak it finds is a leak that happened, not one that resembles one.
 *
 * <p>The log is captured from the root logger rather than from the console: the console appender is
 * bound to a stream at startup, and every framework logger writes through the root regardless of the
 * format configured.
 *
 * <p>{@code REQ-SEC-050}'s gate reads "a test with known test values", and until 2026-09-12 there was
 * none.
 */
@DisplayName("The log")
class LogHygieneIT extends AbstractIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery-staple-42";

  /**
   * A session id this test can recognise in a log line.
   *
   * <p>Chosen rather than generated: {@code MockHttpSession} numbers its sessions from one, and
   * searching a log for "1" finds a timestamp.
   */
  private static final String SESSION_ID = "session-id-that-must-not-be-logged-9f3a";

  @Autowired private AppUserRepository users;
  @Autowired private TenantProvisioningService provisioning;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TransactionTemplate transactions;
  @Autowired private ObjectMapper json;

  @Value("${homeinv.security.url-signing-key-file}")
  private String signingKeyFile;

  private ListAppender<ILoggingEvent> captured;
  private Logger root;
  private Level previous;

  @BeforeEach
  void captureEverything() {
    root = ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(Logger.ROOT_LOGGER_NAME);
    previous = root.getLevel();
    // DEBUG, not INFO: a secret that only appears when somebody turns debugging
    // on is still a secret in a log file, and turning it on is what an operator
    // does when something is wrong.
    root.setLevel(Level.DEBUG);

    captured = new ListAppender<>();
    captured.start();
    root.addAppender(captured);
  }

  @AfterEach
  void stopCapturing() {
    root.detachAppender(captured);
    captured.stop();
    root.setLevel(previous);
  }

  @Test
  @DisplayName("carries no password, no session id and no signing key (REQ-SEC-050)")
  void nothingSecretIsLogged() throws Exception {
    String email = "log-hygiene@example.org";
    String hash = passwordEncoder.encode(PASSWORD);
    provisioning.provision("Log hygiene", createUser(email, hash));

    // A failed attempt, a successful one, and a request that carries the session.
    // Each is a place where a value the caller supplied passes through code that
    // logs.
    mockMvc.perform(
        post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("email", email, "password", "wrong-" + PASSWORD))));

    MockHttpSession session = new MockHttpSession(null, SESSION_ID);
    mockMvc.perform(
        post("/api/v1/auth/login")
            .session(session)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("email", email, "password", PASSWORD))));

    mockMvc.perform(get("/api/v1/items/" + UUID.randomUUID()).session(session));

    String log = capturedLog();
    assertThat(log).as("the suite produced log output at all").isNotBlank();

    assertThat(log).as("no password, right or wrong (REQ-SEC-050)").doesNotContain(PASSWORD);
    assertThat(log)
        .as("no session id: a logged one is a session anybody reading the log can assume")
        .doesNotContain(SESSION_ID);
    assertThat(log)
        .as("no password hash either: it is not a password, and it is an offline cracking target")
        .doesNotContain(hash);
    assertThat(log)
        .as("no signing key: with it, media URLs and pagination cursors can be forged")
        .doesNotContain(signingKey());
  }

  // -------------------------------------------------------------------------

  private String capturedLog() {
    StringBuilder text = new StringBuilder();
    for (ILoggingEvent event : captured.list) {
      text.append(event.getFormattedMessage()).append('\n');
      if (event.getThrowableProxy() != null) {
        text.append(event.getThrowableProxy().getMessage()).append('\n');
      }
    }
    return text.toString();
  }

  /**
   * The key material the running context loaded.
   *
   * @return the key as it stands in the file
   * @throws Exception when the file cannot be read
   */
  private String signingKey() throws Exception {
    return Files.readString(Path.of(signingKeyFile), StandardCharsets.UTF_8).strip();
  }

  private UUID createUser(String email, String hash) {
    UUID userId = UUID.randomUUID();
    transactions.executeWithoutResult(
        status ->
            users.save(AppUser.create(userId, email, "Log Hygiene", "en", hash, Instant.now())));
    return userId;
  }
}
