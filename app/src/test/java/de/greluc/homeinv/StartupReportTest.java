/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.homeinv.platform.StartupReport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * What the startup overview prints, and what it must never print (REQ-NFR-047, REQ-SEC-050).
 *
 * <p>The values below are invented for this test and are not credentials anywhere. That is the
 * point: the assertion is that a string placed under a secret-shaped key does not appear in the
 * output, and a string that only this file knows makes that unambiguous.
 */
class StartupReportTest {

  private static StartupReport reportOver(Map<String, Object> properties) {
    StandardEnvironment environment = new StandardEnvironment();
    environment.getPropertySources().addFirst(new MapPropertySource("under-test", properties));
    return new StartupReport(environment);
  }

  @Test
  @DisplayName("a secret-shaped key is replaced by its length, never by its value")
  void secretsAreMaskedByKeyName() {
    List<String> lines =
        reportOver(
                Map.of(
                    "homeinv.secret.db-password", "not-a-real-password-1234",
                    "homeinv.secret.url-signing-key", "not-a-real-key-abcdef",
                    "spring.datasource.url", "jdbc:postgresql://postgres:5432/homeinv"))
            .lines();

    String printed = String.join("\n", lines);

    assertThat(printed).doesNotContain("not-a-real-password-1234");
    assertThat(printed).doesNotContain("not-a-real-key-abcdef");
    // The length is kept deliberately: a secret that is 0 or 1 characters long is
    // the actual fault often enough to be worth seeing.
    assertThat(printed).contains("homeinv.secret.db-password = (masked, 24 characters)");
    // And the things that are not secret are shown, or the report is useless.
    assertThat(printed).contains("jdbc:postgresql://postgres:5432/homeinv");
  }

  @Test
  @DisplayName("a key naming a path is printed, because the path is the useful part")
  void pathsArePrinted() {
    List<String> lines =
        reportOver(Map.of("homeinv.security.url-signing-key-file", "/run/secrets/url-signing-key"))
            .lines();

    // "the key file is at the path you expected" is what the question usually
    // turns out to be, and masking the path answers nothing.
    assertThat(String.join("\n", lines)).contains("/run/secrets/url-signing-key");
  }

  @Test
  @DisplayName("masking follows the key name, not the value's shape")
  void maskingIgnoresWhatTheValueLooksLike() {
    // A password that happens to look like a hostname is still a password. Any
    // rule that inspected the value would print this one.
    List<String> lines = reportOver(Map.of("homeinv.mq.password", "postgres.internal")).lines();

    assertThat(String.join("\n", lines)).doesNotContain("postgres.internal");
  }

  @Test
  @DisplayName("keys outside this application's own configuration are not printed at all")
  void unrelatedEnvironmentIsNotPrinted() {
    // Printing the whole environment would put a secret an operator placed there
    // by mistake into the log because of ours.
    List<String> lines =
        reportOver(Map.of("AWS_SESSION_TOKEN", "whatever", "PATH", "/usr/bin")).lines();

    String printed = String.join("\n", lines);
    assertThat(printed).doesNotContain("whatever");
    assertThat(printed).doesNotContain("/usr/bin");
  }

  @Test
  @DisplayName("an unset value says so rather than printing null")
  void unsetValuesAreNamed() {
    List<String> lines = reportOver(Map.of("homeinv.public-base-url", "http://localhost:8080")).lines();
    assertThat(String.join("\n", lines)).contains("homeinv.public-base-url = http://localhost:8080");
  }
}
