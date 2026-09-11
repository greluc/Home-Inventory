/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Reads every secret the deployment mounts and makes it a property.
 *
 * <h2>The contract this implements</h2>
 *
 * <p>A secret reaches this application as a <em>file</em>, and an environment variable names the
 * path to it: {@code HOMEINV_DB_PASSWORD_FILE=/run/secrets/db-password} (06 §6.11). The value
 * itself is never an environment variable, because an environment is readable by anything that can
 * list the process, appears in crash dumps and in orchestration descriptions, and survives in shell
 * history (REQ-SEC-050).
 *
 * <p>This post-processor turns each such variable into the property
 * {@code homeinv.secret.<name>}, where {@code <name>} is the mount's own name —
 * {@code HOMEINV_DB_PASSWORD_FILE} becomes {@code homeinv.secret.db-password}. The rest of the
 * configuration then refers to {@code ${homeinv.secret.db-password}} and never knows a file was
 * involved.
 *
 * <h2>Why a post-processor and not a bean</h2>
 *
 * <p>Because {@code spring.datasource.password} is resolved while the data source is built, long
 * before any bean of ours exists. A bean would be too late for the one value it is most needed for.
 *
 * <h2>What it does when a file is missing</h2>
 *
 * <p>Nothing, deliberately. An unreadable file leaves the property unset, and the placeholder that
 * references it then fails with Spring's own message naming the property — which is more useful
 * than a failure here naming a path. What must never happen is a generated default, and none is
 * produced: a generated key works perfectly until the second instance starts with a different one
 * (CLAUDE.md, Security; REQ-NFR-046).
 */
public final class SecretFiles implements EnvironmentPostProcessor {

  /** The prefix a secret-path variable carries. */
  private static final String PREFIX = "HOMEINV_";

  /** The suffix that marks a variable as naming a path rather than holding a value. */
  private static final String SUFFIX = "_FILE";

  /** Where the resolved values are published. */
  static final String PROPERTY_PREFIX = "homeinv.secret.";

  /** The name this property source carries in the environment, for the startup report. */
  static final String SOURCE_NAME = "homeinv-mounted-secrets";

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication app) {
    Map<String, Object> resolved = new HashMap<>();

    environment.getSystemEnvironment().forEach((variable, path) -> {
      if (!variable.startsWith(PREFIX) || !variable.endsWith(SUFFIX) || path == null) {
        return;
      }
      String name = secretName(variable);
      String content = read(String.valueOf(path));
      if (content != null) {
        resolved.put(PROPERTY_PREFIX + name, content);
      }
    });

    if (!resolved.isEmpty()) {
      // First, so a mounted secret wins over anything else that happens to define
      // the same property. There is no legitimate case for overriding a mounted
      // secret from a file in the image.
      environment.getPropertySources().addFirst(new MapPropertySource(SOURCE_NAME, resolved));
    }
  }

  /**
   * The secret's name, as the deployment mounts it.
   *
   * <p>{@code HOMEINV_DB_PASSWORD_FILE} → {@code db-password}. The transformation is the inverse of
   * the one {@code deploy/generate.py} applies, and the two are the same rule written twice — which
   * is why the generator derives its variable names mechanically rather than from a table.
   *
   * @param variable the environment variable name
   * @return the secret name
   */
  private static String secretName(String variable) {
    return variable
        .substring(PREFIX.length(), variable.length() - SUFFIX.length())
        .toLowerCase(Locale.ROOT)
        .replace('_', '-');
  }

  /**
   * Reads a secret file.
   *
   * <p>Trailing whitespace is stripped, because a file written with {@code echo} ends in a newline
   * and a password with a newline in it fails authentication in a way that looks like a wrong
   * password. Leading whitespace is kept: it could legitimately be part of a key.
   *
   * @param path the file to read
   * @return its content, or {@code null} when it cannot be read
   */
  private static String read(String path) {
    try {
      return Files.readString(Path.of(path), StandardCharsets.UTF_8).stripTrailing();
    } catch (IOException | RuntimeException unreadable) {
      // Silent on purpose - see the class comment. Logging here would run before
      // logging is configured, and a warning about a secret is worth getting to
      // the right place rather than to the console early.
      return null;
    }
  }
}
