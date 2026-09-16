/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * What the system insists on knowing about a person, and what it never sends anywhere
 * (REQ-PRIV-001, REQ-PRIV-002).
 *
 * <p>Both are promises that decay silently. A mandatory field is added because one feature wanted
 * it and then everybody has to supply it for ever; a telemetry library arrives as a transitive
 * dependency of something else and starts reporting before anybody reads the release notes. Neither
 * shows up as a failure — which is why both are a test rather than a sentence in a document.
 */
@DisplayName("Personal data")
class PersonalDataIT extends AbstractIntegrationTest {

  /**
   * What an account may require, and nothing else.
   *
   * <p>`REQ-PRIV-001` names three: e-mail, display name and language. The password is here because
   * it is a credential rather than personal data — the account could not authenticate without one —
   * and `locale` carries a default, so it is never asked for even though every row has it.
   */
  private static final Set<String> MAY_BE_MANDATORY =
      Set.of("email", "display_name", "password_hash");

  /**
   * Artefact names that report to somebody else's server.
   *
   * <p>Matched against the jar file names on the classpath rather than against loaded packages: a
   * library that is present and not yet used is exactly the case worth catching, and a loaded-package
   * check would pass until the first call.
   *
   * <p>Deliberately <b>not</b> including OpenTelemetry: tracing is {@code REQ-NFR-044}, it is opt-in,
   * and it exports to a collector the operator runs. "No telemetry" is about analytics that leave the
   * deployment for a vendor, which is what this list names.
   */
  private static final List<String> ANALYTICS_ARTEFACTS =
      List.of(
          "google-analytics",
          "analytics-java",
          "segment",
          "sentry",
          "posthog",
          "mixpanel",
          "amplitude",
          "bugsnag",
          "newrelic",
          "dd-java-agent",
          "datadog",
          "appdynamics");

  @Autowired private JdbcClient jdbc;

  @Test
  @DisplayName("asks a person for nothing beyond an address, a name and a language")
  void onlyThreeMandatoryFields() {
    List<String> mandatory =
        jdbc.sql(
                """
                select column_name
                from information_schema.columns
                where table_schema = 'identity'
                  and table_name = 'app_user'
                  and is_nullable = 'NO'
                  and column_default is null
                order by column_name
                """)
            .query(String.class)
            .list();

    // A fourth mandatory column is how "the only mandatory data is e-mail, a
    // name and a language" stops being true — one feature wants a phone number
    // and everybody has to supply one from then on. Adding one deliberately
    // means amending REQ-PRIV-001 first, which is the friction this test is.
    assertThat(mandatory)
        .as("mandatory columns on identity.app_user (REQ-PRIV-001)")
        .containsExactlyInAnyOrderElementsOf(MAY_BE_MANDATORY);
  }

  @Test
  @DisplayName("carries no analytics library that could report anywhere")
  void noTelemetry() {
    // REQ-PRIV-002: no telemetry and no analytics service, neither built in nor
    // optional. The network half — that api and worker reach nothing outside the
    // deployment — is REQ-PRIV-003 and is proved by the connectivity suite
    // against a running stack. This is the other half, and it is the one a
    // transitive dependency can break without anybody making a decision.
    List<String> classpath =
        List.of(System.getProperty("java.class.path", "").split(java.io.File.pathSeparator));
    List<String> present =
        classpath.stream()
            .map(entry -> entry.substring(entry.lastIndexOf(java.io.File.separatorChar) + 1))
            .map(name -> name.toLowerCase(java.util.Locale.ROOT))
            .filter(name -> ANALYTICS_ARTEFACTS.stream().anyMatch(name::contains))
            .distinct()
            .toList();

    assertThat(present)
        .as("analytics libraries on the runtime classpath (REQ-PRIV-002)")
        .isEmpty();
  }
}
