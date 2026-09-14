/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import java.time.Duration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * The base class for the tests that need a real OpenSearch (REQ-SRCH-005, ADR-0008).
 *
 * <p>Separate from {@link AbstractIntegrationTest} and used by the search tests alone, decided with
 * the owner on 2026-09-14. OpenSearch wants a gigabyte and the better part of a minute to start,
 * and every other integration test in this repository is about a system that does not have one —
 * the {@code minimal} profile never gets it, so running the whole suite against it would be testing
 * a deployment nobody ships.
 *
 * <p>The consequence is a second Spring context, because the property set differs. That is the
 * point rather than an accident: these tests exercise the engine an installation chooses, and the
 * others exercise the one every installation has.
 *
 * <h2>No security plugin here, and TLS is off</h2>
 *
 * <p>The deployment's OpenSearch speaks TLS and authenticates its caller (ADR-0044); this one does
 * neither, because a container that generates its own certificate would be testing certificate
 * generation. What the deployment's configuration buys is checked where it is written —
 * {@code deploy/services.yaml} and its generated {@code internal_users.yml} — and what is checked
 * here is the search.
 *
 * <h2>The {@code worker} profile is on</h2>
 *
 * <p>Because the indexing consumer is the worker's, as every consumer here is: {@code api} and
 * {@code worker} run the same image and the profile decides which does the work off the request
 * thread. A test that left it off would publish the events, complete the outbox, and index nothing
 * — which is exactly what happened before this line existed.
 *
 * <p>{@code SearchEngineProperties} treats anything that is not plainly {@code http} as TLS and
 * therefore as pinned, so the plain URL below is exactly the shape that skips the pin: a test can
 * reach a container without a certificate, and a deployment that misspells its scheme cannot
 * accidentally do the same.
 */
@org.springframework.test.context.ActiveProfiles({"test", "worker"})
public abstract class AbstractSearchIntegrationTest extends AbstractIntegrationTest {

  /**
   * A single-node OpenSearch on the image {@code deploy/services.yaml} pins.
   *
   * <p>The same digest the deployment runs, like every other container here: an integration test
   * against a different version is a test of a system nobody has (CLAUDE.md, Build).
   */
  @SuppressWarnings("resource")
  protected static final GenericContainer<?> OPENSEARCH =
      new GenericContainer<>(ProductionImages.of("opensearch"))
          .withExposedPorts(9200)
          .withEnv("discovery.type", "single-node")
          .withEnv("DISABLE_SECURITY_PLUGIN", "true")
          .withEnv("DISABLE_INSTALL_DEMO_CONFIG", "true")
          .withEnv("bootstrap.memory_lock", "false")
          // Half a gigabyte of heap rather than the image's default quarter of
          // the host: a CI runner has other containers to hold.
          .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms512m -Xmx512m")
          .waitingFor(
              Wait.forHttp("/_cluster/health")
                  .forPort(9200)
                  .forStatusCode(200)
                  .withStartupTimeout(Duration.ofMinutes(3)));

  static {
    OPENSEARCH.start();
  }

  /**
   * Points the application at the container and switches the engine on.
   *
   * @param registry where the properties go
   */
  @DynamicPropertySource
  static void openSearchProperties(DynamicPropertyRegistry registry) {
    registry.add("homeinv.search.engine", () -> "opensearch");
    registry.add(
        "homeinv.search.url",
        () -> "http://" + OPENSEARCH.getHost() + ":" + OPENSEARCH.getMappedPort(9200));
    registry.add("homeinv.search.username", () -> "irrelevant-without-the-security-plugin");
    registry.add("homeinv.search.password", () -> "irrelevant-without-the-security-plugin");
    // The indexing path is asynchronous and swallows what it cannot do, because
    // a derived store may fail (CLAUDE.md rule 10). In a test that silence is
    // the difference between "not indexed yet" and "not indexed at all", so the
    // block logs at debug here.
    registry.add("logging.level.de.greluc.homeinv.search", () -> "DEBUG");
    registry.add("logging.level.org.springframework.modulith", () -> "DEBUG");
  }
}
