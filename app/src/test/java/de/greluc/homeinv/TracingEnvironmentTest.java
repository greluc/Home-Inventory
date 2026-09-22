/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.homeinv.platform.TracingEnvironment;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

/**
 * One knob decides whether this deployment traces, and an empty one means off (REQ-NFR-044).
 *
 * <h2>Why this is worth a test</h2>
 *
 * <p>Because the failure it prevents is a deployment that will not start. A container runtime
 * cannot pass "unset": the generated Compose file and the Quadlet units give every variable an
 * empty default, and Spring Boot's {@code @ConditionalOnProperty} treats the empty string as a
 * value that is present. Without the translation below, every installation that never asked for
 * tracing would build an OTLP exporter for the address {@code ""} — and the first anybody would
 * hear of it is a stack that does not come up after an upgrade.
 *
 * <p>No container: this is a property derivation and nothing else.
 */
@DisplayName("Tracing")
class TracingEnvironmentTest {

  /** Boot's switch for OpenTelemetry itself, which is what "inactive" needs. */
  private static final String ENABLED = "management.opentelemetry.enabled";

  /** Boot's OTLP address, which is what its auto-configuration conditions actually read. */
  private static final String BOOT_ENDPOINT =
      "management.opentelemetry.tracing.export.otlp.endpoint";

  /** Our one knob. */
  private static final String OURS = "homeinv.tracing.endpoint";

  /** Where the observation-to-span bridge is switched off. */
  private static final String EXCLUDE = "spring.autoconfigure.exclude";

  @Test
  @DisplayName("is off when no collector is named")
  void offWhenUnconfigured() {
    MockEnvironment environment = new MockEnvironment();

    process(environment);

    assertThat(environment.getProperty(ENABLED))
        .as("the tracer itself, not merely its exporter: a tracer with nowhere to send would still"
            + " sample and still start a span per request")
        .isEqualTo("false");
    assertThat(environment.getProperty(BOOT_ENDPOINT))
        .as("no endpoint is invented")
        .isNull();
    assertThat(environment.getProperty(EXCLUDE))
        .as("and the bridge is excluded, because disabled is not absent: Boot disabled still"
            + " installs an SDK that records spans into a processor which discards them")
        .contains("MicrometerTracingAutoConfiguration")
        .contains("OpenTelemetryTracingAutoConfiguration")
        .contains("OtlpTracingAutoConfiguration");
  }

  @Test
  @DisplayName("is off when the collector is named as the empty string")
  void offWhenTheKnobIsEmpty() {
    // Exactly what `HOMEINV_TRACING_ENDPOINT=` produces, which is what both
    // generated runtimes pass when the operator has set nothing.
    MockEnvironment environment = new MockEnvironment().withProperty(OURS, "   ");

    process(environment);

    assertThat(environment.getProperty(ENABLED)).isEqualTo("false");
    assertThat(environment.getProperty(BOOT_ENDPOINT)).isNull();
    assertThat(environment.getProperty(EXCLUDE)).contains("MicrometerTracingAutoConfiguration");
  }

  @Test
  @DisplayName("exports to the collector the operator named")
  void onWhenConfigured() {
    MockEnvironment environment =
        new MockEnvironment().withProperty(OURS, " http://otel-collector:4318/v1/traces ");

    process(environment);

    assertThat(environment.getProperty(BOOT_ENDPOINT))
        .as("trimmed, because an environment file is edited by hand")
        .isEqualTo("http://otel-collector:4318/v1/traces");
    assertThat(environment.getProperty(ENABLED))
        .as("nothing switches the tracer off, and nothing switches it on either — Boot's own"
            + " default of enabled is left to stand")
        .isNull();
    assertThat(environment.getProperty(EXCLUDE))
        .as("and nothing is excluded, or there would be a collector and nothing to send it")
        .isNull();
  }

  @Test
  @DisplayName("leaves a configuration written in Boot's own properties alone")
  void bootsOwnPropertyCountsAsConfigured() {
    // An operator who sets Boot's property directly has configured tracing.
    // Switching it off underneath them for the sole reason that they did not
    // use our variable would be this class breaking a working deployment.
    MockEnvironment environment =
        new MockEnvironment().withProperty(BOOT_ENDPOINT, "http://collector:4318/v1/traces");

    process(environment);

    assertThat(environment.getProperty(ENABLED)).isNull();
    assertThat(environment.getProperty(BOOT_ENDPOINT))
        .as("theirs, unaltered")
        .isEqualTo("http://collector:4318/v1/traces");
    assertThat(environment.getProperty(EXCLUDE)).isNull();
  }

  @Test
  @DisplayName("brings no exporter that pushes anywhere on its own")
  void nothingPushesWithoutBeingAsked() {
    // `spring-boot-starter-opentelemetry` would have been the short way to the
    // tracing auto-configuration, and it also brings `micrometer-registry-otlp`,
    // which PUSHES METRICS to `http://localhost:4318/v1/metrics` every minute by
    // default. A core container that opens a connection nobody configured is
    // what ADR-0026 exists to prevent, and a transitive dependency is exactly
    // how one comes back.
    //
    // The jar names rather than the loaded classes, like `PersonalDataIT`: a
    // library that is present and not yet used is the case worth catching.
    String classpath = System.getProperty("java.class.path", "");
    List<String> pushers =
        java.util.Arrays.stream(classpath.split(java.io.File.pathSeparator))
            .map(entry -> entry.substring(entry.lastIndexOf(java.io.File.separatorChar) + 1))
            .filter(name -> name.contains("micrometer-registry-otlp"))
            .toList();

    assertThat(pushers)
        .as("an exporter with a default endpoint on the classpath (ADR-0026, REQ-NFR-044)")
        .isEmpty();
  }

  @Test
  @DisplayName("leaves an installation that switched OpenTelemetry on itself alone")
  void anExplicitEnableCountsAsConfigured() {
    // An operator wiring their own exporter -- and the integration tests, which
    // record spans into a list instead of sending them. Excluding the bridge
    // underneath either would switch off the thing they just switched on.
    MockEnvironment environment = new MockEnvironment().withProperty(ENABLED, "true");

    process(environment);

    assertThat(environment.getProperty(ENABLED)).isEqualTo("true");
    assertThat(environment.getProperty(EXCLUDE)).isNull();
  }

  @Test
  @DisplayName("adds the bridge to an exclusion list rather than replacing it")
  void anExistingExclusionSurvives() {
    MockEnvironment environment = new MockEnvironment().withProperty(EXCLUDE, "com.example.Other");

    process(environment);

    assertThat(environment.getProperty(EXCLUDE))
        .as("nothing here excludes an auto-configuration today, and discarding somebody else's the"
            + " first time there is one is not a failure anybody would look for here")
        .startsWith("com.example.Other,")
        .contains("MicrometerTracingAutoConfiguration");
  }

  /**
   * Runs the post-processor over one environment.
   *
   * @param environment the environment to derive from and add to
   */
  private static void process(MockEnvironment environment) {
    new TracingEnvironment().postProcessEnvironment(environment, new SpringApplication());
  }
}
