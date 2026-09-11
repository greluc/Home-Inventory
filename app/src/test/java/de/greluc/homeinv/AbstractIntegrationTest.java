/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.junit.jupiter.api.BeforeEach;
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
public abstract class AbstractIntegrationTest {

  /**
   * PostgreSQL 18, the version the application targets.
   *
   * <p>Pinned to a digest-free tag here for readability; CI pins the digest, because "the same
   * image as production" is a claim a floating tag cannot support.
   */
  @SuppressWarnings("resource") // Testcontainers closes it with the JVM via Ryuk.
  protected static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:18-alpine"))
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
      new GenericContainer<>(DockerImageName.parse("valkey/valkey:8-alpine")).withExposedPorts(6379);

  static {
    POSTGRES.start();
    VALKEY.start();
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
    mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
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
  }
}
