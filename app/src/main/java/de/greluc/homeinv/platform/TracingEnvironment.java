/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.platform;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * One knob decides whether this deployment traces at all (REQ-NFR-044, 13 §13.4).
 *
 * <h2>The knob</h2>
 *
 * <p>{@code HOMEINV_TRACING_ENDPOINT} — the OTLP address of the collector the <b>operator</b> runs.
 * Set it and the application exports spans to it; leave it empty and tracing is off, which is the
 * requirement's "inactive when unconfigured" read literally: no tracer, no spans, no sampler, and
 * the {@code traceId} of 13 §13.2 goes on coming from {@link
 * de.greluc.homeinv.rest.TraceIdFilter} exactly as it did before there was any tracing at all.
 *
 * <h2>Why this is a post-processor and not two lines of YAML</h2>
 *
 * <p>Because a deployment cannot express "absent" in an environment variable. The generated
 * Compose file and the Quadlet units pass every variable through with an empty default — that is
 * how {@code HOMEINV_PLUGIN_HOST_PORT=0} says "no host channel" — and Spring Boot's
 * {@code @ConditionalOnProperty} counts the empty string as a value that is <i>present</i>. Written
 * as {@code endpoint: ${HOMEINV_TRACING_ENDPOINT:}} in {@code application.yaml}, every default
 * deployment would therefore build an OTLP exporter for the address {@code ""} and fail to start.
 *
 * <p>So the empty string is turned into the absence it means, here, before anything reads a
 * property. It is the same reason {@link SecretFiles} is a post-processor: what the data source and
 * the auto-configuration conditions read has to be right before any bean of ours exists.
 *
 * <h2>What it sets</h2>
 *
 * <ul>
 *   <li><b>Configured</b> — {@code management.opentelemetry.tracing.export.otlp.endpoint}, which is
 *       the property Boot's OTLP auto-configuration gates its connection details on, and therefore
 *       its exporter.
 *   <li><b>Not configured</b> — {@code management.opentelemetry.enabled=false} <b>and</b> three
 *       entries in {@code spring.autoconfigure.exclude}. Both are needed, and finding that out is
 *       the reason this class has a second half.
 * </ul>
 *
 * <h2>Why disabling is not enough</h2>
 *
 * <p>Spring Boot disabled is not Spring Boot absent. {@code management.opentelemetry.enabled=false}
 * installs a {@code disabledOpenTelemetrySdk} whose tracer provider is real, whose sampler says yes
 * and whose spans are therefore <b>recorded</b> — into a processor that discards them. The bridge
 * goes on writing {@code traceId} and {@code spanId} into the MDC from a trace that exists nowhere,
 * and an allocation per observation is paid by every deployment that never asked for tracing. That
 * is the "active and discarding" that the Java agent was rejected for (ADR-0082); arriving at
 * it by another route would be no better.
 *
 * <p>So the three auto-configurations that build the bridge are excluded outright. What remains is
 * {@code NoopTracerAutoConfiguration}'s {@code Tracer.NOOP}, which satisfies anything that asks for
 * a tracer and does nothing — and {@link de.greluc.homeinv.rest.TraceIdFilter}, which is then the
 * single thing that fills {@code traceId}, exactly as before tracing existed.
 *
 * <p>Naming Boot's own classes is a coupling and it is a visible one: {@code TracingOffIT} asserts
 * that no {@code TracingObservationHandler} bean exists, so an exclude that stops matching after an
 * upgrade fails the build rather than quietly switching tracing back on.
 *
 <h2>Precedence</h2>
 *
 * <p>Added <b>first</b>, and nothing is taken away by it, because every value published here is
 * either derived from somebody else's silence or a <i>merge</i> of what they said:
 *
 * <ul>
 *   <li>{@code management.opentelemetry.enabled=false} and the excludes are written only when no
 *       endpoint is configured by any route and nobody switched OpenTelemetry on themselves;
 *   <li>the exclusion list is published with the existing entries still in it, which is the reason
 *       this source has to come first: added last it would be shadowed by the value it merged, and
 *       the bridge would go on running — silently, because the property would still read
 *       correctly to anyone who looked;
 *   <li>{@code management.opentelemetry.tracing.export.otlp.endpoint} is written only when Boot's
 *       own is unset, so an operator who configures Boot directly is never second-guessed.
 * </ul>
 *
 * <h2>The collector is inside the deployment</h2>
 *
 * <p>It has to be. {@code api} and {@code worker} have no outbound route to the internet
 * (ADR-0026), and nothing here changes that: the endpoint names a collector on a network segment
 * those containers are already on, and what that collector forwards to is the operator's business
 * and outside this topology. An address outside the deployment does not fail loudly — it simply
 * never connects, which is the correct outcome for a container that is not supposed to reach out.
 */
public final class TracingEnvironment implements EnvironmentPostProcessor {

  /** Our knob, as a property. Relaxed binding maps {@code HOMEINV_TRACING_ENDPOINT} onto it. */
  static final String ENDPOINT = "homeinv.tracing.endpoint";

  /** Boot's own, which is what the auto-configuration conditions actually read. */
  static final String BOOT_ENDPOINT = "management.opentelemetry.tracing.export.otlp.endpoint";

  /**
   * Boot's switch for OpenTelemetry itself, as opposed to its exporter.
   *
   * <p>This one and not {@code management.tracing.export.enabled}, which gates only the exporters,
   * nor {@code management.tracing.enabled}, which Boot 4 removed. Its own documentation is the
   * reason it is the right one: <i>"If OpenTelemetry is disabled, only propagators are configured.
   * Metrics, traces, and logging will use no-op implementations."</i> No tracer provider that
   * samples, no handler that turns an observation into a span — which is what "inactive" has to
   * mean to be worth asserting.
   */
  static final String BOOT_ENABLED = "management.opentelemetry.enabled";

  /** Where auto-configurations are turned off, which is a Spring Boot property and not ours. */
  static final String EXCLUDE = "spring.autoconfigure.exclude";

  /**
   * The three that build the observation-to-span bridge.
   *
   * <p>Not the SDK itself, which the logging path shares, and not
   * {@code NoopTracerAutoConfiguration}, whose whole job is to leave a {@code Tracer} bean that
   * does nothing for the things that ask for one.
   */
  private static final List<String> BRIDGE =
      List.of(
          "org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration",
          "org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration",
          "org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.OtlpTracingAutoConfiguration");

  /** The name this property source carries in the environment, for the startup report. */
  static final String SOURCE_NAME = "homeinv-tracing";

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication app) {
    String ours = trimmed(environment.getProperty(ENDPOINT));
    String boots = trimmed(environment.getProperty(BOOT_ENDPOINT));
    // `parseBoolean` rather than `equalsIgnoreCase`, which SpotBugs flags:
    // case folding is locale-sensitive and a Turkish locale folds "I"
    // somewhere other than where this would expect it.
    boolean enabledOutright = Boolean.parseBoolean(trimmed(environment.getProperty(BOOT_ENABLED)));

    Map<String, Object> derived = new LinkedHashMap<>();
    if (ours.isEmpty() && boots.isEmpty() && !enabledOutright) {
      derived.put(BOOT_ENABLED, "false");
      derived.put(EXCLUDE, excluding(environment));
    } else if (!ours.isEmpty() && boots.isEmpty()) {
      derived.put(BOOT_ENDPOINT, ours);
    }

    if (!derived.isEmpty()) {
      environment.getPropertySources().addFirst(new MapPropertySource(SOURCE_NAME, derived));
    }
  }

  /**
   * The exclusion list, with the bridge added to whatever was already there.
   *
   * <p>Nothing in this application excludes an auto-configuration today, so in practice this is the
   * bridge alone. It reads the existing value regardless, because the alternative is a class that
   * silently discards somebody else's exclusion the first time there is one.
   *
   * @param environment the environment being post-processed
   * @return the comma-separated list to publish
   */
  private static String excluding(ConfigurableEnvironment environment) {
    String existing = trimmed(environment.getProperty(EXCLUDE));
    String bridge = String.join(",", BRIDGE);
    return existing.isEmpty() ? bridge : existing + "," + bridge;
  }

  // `excluding` reads the property this class is about to shadow, which is
  // deliberate and is why the merge happens before the source is added: what is
  // published contains what was there.


  /**
   * A property value with no surrounding space, and never {@code null}.
   *
   * @param value what the environment returned, possibly {@code null}
   * @return the trimmed value, or the empty string
   */
  private static String trimmed(String value) {
    return value == null ? "" : value.trim();
  }
}
