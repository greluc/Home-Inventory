/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * The base every integration test extends: a real PostgreSQL and a real Valkey.
 *
 * <h2>Why no substitute database</h2>
 *
 * <p>H2 and its relatives are forbidden here, and not out of purism. Everything these tests exist
 * to prove — row-level security, {@code ltree} paths, generated {@code tsvector} columns, the
 * {@code SECURITY DEFINER} bootstrap — either does not exist in a substitute or behaves differently
 * there. A green test against H2 would say nothing about the property it claims to check.
 *
 * <h2>Why the application connects as {@code homeinv_app}</h2>
 *
 * <p>Because that is the role it has in production: {@code NOBYPASSRLS}, no ownership, no DDL. A
 * test suite running as the owner would pass every isolation test for the wrong reason — the owner
 * is exempt from policies unless {@code FORCE} is set, and proving that {@code FORCE} is set is
 * half of what these tests are for.
 *
 * <p>The containers are static, so one pair serves the whole suite. Starting PostgreSQL per class
 * would multiply a fifteen-second cost by the number of test classes, and the suite would stop
 * being run.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
// On the base class, so every integration test gets it without repeating the
// import. A nested @TestConfiguration would not do: Spring Boot discovers those
// on the test class itself, not on its superclass, which is a difference that
// shows up only in the one test that actually stores a blob.
@Import(TestBlobStore.class)
public abstract class AbstractIntegrationTest {

  /**
   * PostgreSQL 18, the version the application targets.
   *
   * <p>The coordinates come from {@code deploy/services.yaml} through {@link ProductionImages},
   * digest first — so this is the image the deployment runs, and it becomes digest-pinned the day
   * the matrix names one, without a line here changing (REQ-NFR-027, A13 in ADR-0000).
   *
   * <p>This comment said "CI pins the digest" until 2026-09-12. It did not — nothing in
   * {@code ci.yml} pinned anything — which is a claim with no mechanism, sitting in the file that
   * would have implemented it.
   */
  @SuppressWarnings("resource") // Testcontainers closes it with the JVM via Ryuk.
  protected static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(ProductionImages.postgresBase().asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("homeinv")
          .withUsername("postgres")
          .withPassword("test-superuser")
          // The production role script, then the passwords a container needs.
          // Running the real script is what keeps NOBYPASSRLS in the test path.
          .withCopyFileToContainer(
              MountableFile.forClasspathResource("db/00-roles.sql"),
              "/docker-entrypoint-initdb.d/00-roles.sql")
          .withCopyFileToContainer(
              MountableFile.forClasspathResource("db/test-roles.sql"),
              "/docker-entrypoint-initdb.d/01-test-roles.sql");

  /** Valkey, where sessions and the login throttle live. */
  @SuppressWarnings("resource")
  protected static final GenericContainer<?> VALKEY =
      new GenericContainer<>(ProductionImages.of("valkey")).withExposedPorts(6379);

  /**
   * RabbitMQ, where media events travel from {@code api} to {@code worker}.
   *
   * <p>A real broker rather than a stub, for the same reason PostgreSQL is real: the thing worth
   * proving is that an event published in one process arrives in another, and a stub proves that a
   * stub works. It is in the base class because the exchange is declared at context startup, and a
   * context that cannot reach a broker starts anyway and then fails at the first publish — which
   * would make every media test fail for a reason that has nothing to do with media.
   */
  @SuppressWarnings("resource")
  protected static final GenericContainer<?> RABBITMQ =
      new GenericContainer<>(ProductionImages.of("rabbitmq")).withExposedPorts(5672);

  /**
   * A throwaway certificate authority, and the two identities the mTLS hop needs.
   *
   * <p>Generated per run rather than checked in: a private key in the repository is a private key
   * in every clone of it, and `gitleaks` would be right to fail the build over one.
   */
  protected static final TestPki PKI;

  /** The core's client identity, which `api` and `worker` present to the blob store. */
  protected static final TestPki.Identity CORE_IDENTITY;

  /** The blob store's own identity, and the fingerprint the client pins. */
  protected static final TestPki.Identity BLOBSTORE_IDENTITY;

  static {
    try {
      PKI = TestPki.create();
      CORE_IDENTITY = PKI.issue("api");
      BLOBSTORE_IDENTITY = PKI.issue("blobstore");
    } catch (Exception impossible) {
      throw new IllegalStateException("The test PKI could not be created", impossible);
    }

    POSTGRES.start();
    VALKEY.start();
    RABBITMQ.start();
  }

  @Autowired private WebApplicationContext webApplicationContext;

  /**
   * The entry point every test drives the application through.
   *
   * <p>Built by hand rather than injected. Spring Boot 4 split the test
   * auto-configuration modules, and wiring MockMvc through a starter now depends on which of them
   * happens to be present; building it from the context is stable across that and makes the
   * security filter chain's participation explicit rather than implicit.
   */
  protected MockMvc mockMvc;

  /** Builds MockMvc with the real security filter chain in place. */
  @BeforeEach
  void buildMockMvc() {
    // apply(springSecurity()) is what puts the actual filter chain in the path.
    // Without it the tests would exercise the controllers directly and every
    // authentication and CSRF assertion below would pass vacuously.
    //
    // The two filters are added by hand because `webAppContextSetup` registers
    // none of the application's own: they are servlet filters registered with the
    // container, not beans the DispatcherServlet consults. Leaving them out cost
    // nothing visible and hid two properties completely — a request had no
    // `traceId` (REQ-NFR-042) and a body of any size was accepted (REQ-SEC-065) —
    // in exactly the tests written to check them.
    mockMvc =
        MockMvcBuilders.webAppContextSetup(webApplicationContext)
            .addFilters(
                webApplicationContext.getBean(de.greluc.homeinv.rest.TraceIdFilter.class),
                webApplicationContext.getBean(de.greluc.homeinv.rest.JsonBodyLimitFilter.class))
            .apply(springSecurity())
            .build();
  }

  /** The secrets this test enrolled, so that {@link #signIn} can answer a challenge. */
  private final java.util.Map<UUID, String> secondFactorSecrets = new java.util.HashMap<>();

  /**
   * Signs an account in, answering the second factor when it is asked for.
   *
   * <p>A login is one call or two (REQ-AUTH-002), and which one it is depends on the account. A
   * test that wrote out both would be a test about the login rather than about its own subject, and
   * every test here that acts as an {@code OWNER} needs a factor because REQ-AUTH-003 refuses the
   * role without one.
   *
   * <p>The replay guard is rewound before the code is generated. In life the phone shows a new code
   * every thirty seconds and a person signs in once; a test signs in five times in the same step,
   * and waiting out the clock would put half a minute into each of them. What is being rewound is
   * the guard's record, not the guard — {@code SecondFactorIT} proves it works, with no rewinding.
   *
   * @param email the address
   * @param password the password
   * @return the established session
   * @throws Exception when a call fails, which is the test failing
   */
  protected MockHttpSession signIn(String email, String password) throws Exception {
    MockHttpSession session = new MockHttpSession();
    int status =
        mockMvc
            .perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/api/v1/auth/login")
                    .session(session)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .content(
                        "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
            .andReturn()
            .getResponse()
            .getStatus();
    if (status == 200) {
      return session;
    }
    if (status != 401) {
      throw new AssertionError("The login answered " + status + " for " + email);
    }

    UUID userId =
        webApplicationContext
            .getBean(de.greluc.homeinv.identity.infrastructure.AppUserRepository.class)
            .findByEmail(email)
            .orElseThrow(() -> new AssertionError("No account for " + email))
            .getId();
    String secret =
        secondFactorSecrets.computeIfAbsent(
            userId,
            id -> {
              throw new AssertionError("No second factor was enrolled for " + email);
            });

    rewindSecondFactor(userId);
    String code =
        de.greluc.homeinv.identity.application.TotpCodes.generate(
            base32(secret),
            de.greluc.homeinv.identity.application.TotpCodes.stepOf(java.time.Instant.now()));
    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/v1/auth/mfa")
                .session(session)
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.csrf())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\"}"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    return session;
  }

  /**
   * The entity tag a resource currently carries, for the {@code If-Match} a write needs.
   *
   * <p>Every mutating request on a single resource is refused without one (REQ-API-004), so a test
   * about something else — a permission, a role, a refusal for another reason — still has to read
   * the tag first. Two lines in a helper rather than in each of them.
   *
   * @param session the caller's session
   * @param path the resource's path, as a GET would take it
   * @return the quoted tag, ready to be sent back as {@code If-Match}
   * @throws Exception when the read fails, which is the test failing
   */
  protected String eTagOf(MockHttpSession session, String path) throws Exception {
    String tag =
        mockMvc
            .perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                    .session(session))
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andReturn()
            .getResponse()
            .getHeader("ETag");
    if (tag == null) {
      throw new AssertionError(path + " answered no ETag, so nothing can be written to it");
    }
    return tag;
  }

  /**
   * Forgets which time step this account last spent, so the next code is accepted.
   *
   * @param userId the account
   */
  private void rewindSecondFactor(UUID userId) {
    webApplicationContext
        .getBean(org.springframework.transaction.support.TransactionTemplate.class)
        .executeWithoutResult(
            status ->
                webApplicationContext
                    .getBean(org.springframework.jdbc.core.simple.JdbcClient.class)
                    .sql("update identity.credential set last_used_at = null where user_id = ?")
                    .param(userId)
                    .update());
  }

  /**
   * The moment the <b>database</b> is at.
   *
   * <p>For a test that raises something and then asks a delivery run to pick it up. Those rows
   * carry a {@code next_attempt_at} written by {@code now()} in PostgreSQL, and the run compares it
   * against an instant the caller supplies — so a caller supplying {@code Instant.now()} is
   * comparing two clocks in two processes, and a skew of a millisecond makes a row that was just
   * written not yet due. In production the run comes round every thirty seconds and never notices;
   * in a test it is the difference between a pass and a flake.
   *
   * <p>Asking the database for its own clock compares like with like. It is deliberately <b>not</b>
   * "now plus a second": widening the window sweeps up rows other tests scheduled for the future,
   * and this queue is shared — that was tried on 2026-09-20 and made a different test deliver
   * somebody else's message.
   *
   * @return the database's current instant
   */
  protected java.time.Instant databaseNow() {
    return webApplicationContext
        .getBean(org.springframework.jdbc.core.simple.JdbcClient.class)
        .sql("select now()")
        .query(java.time.Instant.class)
        .single();
  }

  /**
   * Base32 back to bytes, which only a test needs.
   *
   * @param encoded the secret as the enrolment returned it
   * @return the raw secret
   */
  private static byte[] base32(String encoded) {
    String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    int buffer = 0;
    int bits = 0;
    for (char c : encoded.toCharArray()) {
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
   * Gives an account a confirmed second factor, without the two-call enrolment.
   *
   * <p>REQ-AUTH-003 refuses every request from an {@code OWNER} or {@code ADMIN} who has none, so
   * most tests here would otherwise begin by enrolling one through the API and generating a code —
   * five calls of ceremony before the thing under test. The whole enrolment loop is proved in
   * {@code SecondFactorIT}; this writes the row that loop would leave behind.
   *
   * <p>The secret is sealed with the same key the application uses, because a row it could not open
   * would be a factor nobody can answer — including the tests that sign in with one.
   *
   * @param userId the account
   * @return the base32 secret, for a test that wants to generate a code from it
   */
  protected String enrolSecondFactor(UUID userId) {
    byte[] secret = de.greluc.homeinv.identity.application.TotpCodes.newSecret();
    java.time.Instant now = java.time.Instant.now();
    de.greluc.homeinv.identity.domain.Credential credential =
        de.greluc.homeinv.identity.domain.Credential.enrol(
            userId,
            de.greluc.homeinv.identity.domain.Credential.TOTP,
            webApplicationContext
                .getBean(de.greluc.homeinv.identity.infrastructure.CredentialKey.class)
                .seal(secret),
            "test authenticator",
            now);
    credential.confirm(now);
    webApplicationContext
        .getBean(org.springframework.transaction.support.TransactionTemplate.class)
        .executeWithoutResult(
            status ->
                webApplicationContext
                    .getBean(
                        de.greluc.homeinv.identity.infrastructure.CredentialRepository.class)
                    .save(credential));
    String encoded = de.greluc.homeinv.identity.application.TotpCodes.base32(secret);
    secondFactorSecrets.put(userId, encoded);
    return encoded;
  }

  /**
   * Points the application at the containers.
   *
   * @param registry the registry Spring fills the properties from
   */
  @DynamicPropertySource
  static void containerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
    registry.add("spring.data.redis.host", VALKEY::getHost);
    registry.add("spring.data.redis.port", () -> VALKEY.getMappedPort(6379));
    registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
    registry.add("spring.rabbitmq.port", () -> RABBITMQ.getMappedPort(5672));
    // The gRPC client is still CONSTRUCTED — the pin and the identity have no
    // default and its constructor refuses without them, which is the behaviour
    // under test elsewhere. It never connects: a gRPC channel is lazy, and the
    // in-memory store above is what actually gets injected.
    registry.add("HOMEINV_BLOBSTORE_ENDPOINT", () -> "blobstore.invalid:8100");
    registry.add("HOMEINV_BLOBSTORE_FINGERPRINT", BLOBSTORE_IDENTITY::fingerprint);
    registry.add("HOMEINV_MTLS_CORE_FILE", () -> CORE_IDENTITY.bundle().toString());
  }
}
