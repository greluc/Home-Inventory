/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.platform;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.stereotype.Component;

/**
 * Logs what this instance is actually configured with, secrets masked (REQ-NFR-047).
 *
 * <h2>Why it exists</h2>
 *
 * <p>Almost every configuration incident is answered by one question — <em>what did the process
 * actually read?</em> — and the answer is normally reconstructed from a deployment description that
 * may not be the one running. Printing it once, at startup, makes it a fact in the log next to the
 * requests it explains.
 *
 * <h2>What is printed</h2>
 *
 * <p>The keys this application's own configuration is built from: {@code homeinv.*}, the datastore
 * coordinates, the server and management listeners, and the active profiles. Not the whole
 * environment — that would print the container's entire variable set, and a secret that arrived
 * there by an operator's mistake would then be in the log because of ours.
 *
 * <h2>How masking works</h2>
 *
 * <p>By the key's <em>name</em>, never by the value's shape. A value cannot be inspected to decide
 * whether it is a credential — that is how a password that happens to look like a hostname reaches
 * a log — so anything whose name contains {@code secret}, {@code password}, {@code credential},
 * {@code token}, {@code key} or {@code salt} is masked whatever it holds. That covers every mounted
 * secret by construction, because {@link SecretFiles} publishes them all under
 * {@code homeinv.secret.}.
 *
 * <p>The exception is a key ending in {@code -file} or {@code .file}, which names a <em>path</em>
 * rather than a credential. Those are printed, and they are among the most useful lines in the
 * report: "the key file is at the path you expected" is what the question usually turns out to be.
 *
 * <p>A masked value is printed as its length and nothing else. The length is useful — a secret that
 * is 0 or 1 characters long is the actual fault often enough — and it is not the secret.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartupReport {

  /** Keys worth printing. Everything else in the environment is somebody else's business. */
  private static final List<String> INTERESTING =
      List.of(
          "homeinv.",
          "spring.application.name",
          "spring.datasource.url",
          "spring.datasource.username",
          "spring.data.redis.",
          "spring.flyway.enabled",
          "spring.flyway.user",
          "spring.jpa.hibernate.ddl-auto",
          "server.port",
          "management.server.");

  /** Names that mean "do not print this", whatever the value looks like. */
  private static final Pattern SECRET_KEY =
      Pattern.compile("(?i)(secret|password|passwd|credential|token|key(store|file)?|salt)");

  /** Keys that name a *path* to a secret rather than one. A path is safe and useful to print. */
  private static final Pattern PATH_KEY = Pattern.compile("(?i)-file$|\\.file$");

  private final ConfigurableEnvironment environment;

  /**
   * Prints the report once the context is up.
   *
   * <p>On {@link ApplicationReadyEvent} rather than earlier, so that logging is fully configured and
   * the output lands wherever the deployment sends it rather than on the console.
   */
  @EventListener(ApplicationReadyEvent.class)
  public void report() {
    log.info(String.join(System.lineSeparator(), lines()));
  }

  /**
   * The report, as the lines it will be logged as.
   *
   * <p>Separate from {@link #report()} so that a test can assert what would be printed. Asserting on
   * captured log output instead would make the test depend on the logging configuration, which is
   * per profile and therefore not the thing under test.
   *
   * @return one line per setting, sorted, secrets masked
   */
  public List<String> lines() {
    TreeMap<String, String> settings = new TreeMap<>();

    for (PropertySource<?> source : environment.getPropertySources()) {
      if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
        continue;
      }
      for (String key : enumerable.getPropertyNames()) {
        if (INTERESTING.stream().noneMatch(key::startsWith)) {
          continue;
        }
        // Resolved through the environment, not read from this source: the value
        // that matters is the one that wins, and placeholders have to be expanded
        // or the report shows `${…}` where the interesting part is.
        settings.putIfAbsent(key, present(key, environment.getProperty(key)));
      }
    }

    List<String> lines = new ArrayList<>();
    lines.add("Configuration in effect:");
    lines.add("  active profiles: " + String.join(", ", environment.getActiveProfiles()));
    settings.forEach((key, value) -> lines.add("  " + key + " = " + value));
    return lines;
  }

  /**
   * A value as it may appear in a log.
   *
   * @param key the property name
   * @param value the resolved value
   * @return the value, or a masked stand-in naming only its length
   */
  private static String present(String key, String value) {
    if (value == null) {
      return "(not set)";
    }
    String lower = key.toLowerCase(Locale.ROOT);
    boolean namesAPath = PATH_KEY.matcher(lower).find();
    if (!namesAPath && SECRET_KEY.matcher(lower).find()) {
      return "(masked, %d characters)".formatted(value.length());
    }
    return value;
  }
}
