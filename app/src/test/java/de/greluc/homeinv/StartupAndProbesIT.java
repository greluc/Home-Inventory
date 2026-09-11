/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.homeinv.platform.SchemaVersion;
import de.greluc.homeinv.platform.SchemaVersionCheck;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.health.registry.HealthContributorRegistry;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * The operational surface: what the probes answer and what the pool hands back.
 *
 * <p>These are the properties an operator depends on and no feature test touches — a liveness probe
 * that consults the database, a readiness probe that does not, and a connection that returns to the
 * pool still carrying somebody's tenant.
 */
class StartupAndProbesIT extends AbstractIntegrationTest {

  @Autowired private HealthContributorRegistry healthContributors;
  @Autowired private SchemaVersion schemaVersion;
  @Autowired private SchemaVersionCheck schemaVersionCheck;
  @Autowired private DataSource dataSource;
  @Autowired private ConfigurableEnvironment environment;

  @Test
  @DisplayName("the probe groups exist, and livez consults nothing external (REQ-NFR-045)")
  void probeGroupsAreConfiguredAsDocumented() {
    // The membership is configuration, and configuration is exactly what drifts.
    // `livez` must contain the liveness state and nothing else: an external check
    // there turns a database outage into a restart loop, which is the one
    // response guaranteed to make the outage worse (13 §13.3).
    assertThat(environment.getProperty("management.endpoint.health.group.livez.include"))
        .isEqualTo("livenessState");
    assertThat(environment.getProperty("management.endpoint.health.group.livez.additional-path"))
        .isEqualTo("management:/livez");

    // `readyz` has to consult both, or a skipped migration takes traffic.
    assertThat(environment.getProperty("management.endpoint.health.group.readyz.include"))
        .contains("db")
        .contains("schemaVersionCheck");
    assertThat(environment.getProperty("management.endpoint.health.group.readyz.additional-path"))
        .isEqualTo("management:/readyz");
  }

  @Test
  @DisplayName("readyz names contributors that actually exist")
  void readinessNamesRealContributors() {
    // A group that names a contributor nobody registered is silently empty, and
    // the probe then reports UP for a check that never ran.
    assertThat(healthContributors.getContributor("db")).isNotNull();
    assertThat(healthContributors.getContributor("schemaVersionCheck")).isNotNull();
  }

  @Test
  @DisplayName("the schema check compares against the migrations this build ships (REQ-SEC-101)")
  void schemaCheckComparesAgainstTheShippedMigrations() {
    // Nothing is hardcoded: adding V11 raises the expectation by existing, so
    // there is no number anybody can forget to update.
    assertThat(schemaVersion.expected()).isGreaterThanOrEqualTo(10);
    assertThat(schemaVersion.applied()).isEqualTo(schemaVersion.expected());
    assertThat(schemaVersion.satisfied()).isTrue();
    assertThat(schemaVersionCheck.health().getStatus()).isEqualTo(Status.UP);
  }

  @Test
  @DisplayName("the application role can read the migration history and nothing else in that schema")
  void theHistoryIsReadableAndTheSchemaIsNot() throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {

      try (ResultSet rows =
          statement.executeQuery("select count(*) from flyway.flyway_schema_history")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getInt(1)).isPositive();
      }

      // Reading which migrations ran is metadata. Claiming one ran is not: the
      // role must not be able to write the very fact the startup check reads.
      assertThat(canWriteHistory(statement)).isFalse();
    }
  }

  @Test
  @DisplayName("a connection returns to the pool without a tenant context (REQ-SEC-006)")
  void theTenantContextDoesNotSurviveTheConnection() throws Exception {
    UUID tenant = UUID.randomUUID();

    // Exactly what the transaction manager does, on a connection of our own.
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("select set_config('app.tenant_id', '" + tenant + "', true)");
      try (ResultSet rows = statement.executeQuery("select current_setting('app.tenant_id', true)")) {
        rows.next();
        assertThat(rows.getString(1)).isEqualTo(tenant.toString());
      }
      // `true` is the local flag: the setting belongs to the transaction and is
      // discarded when it ends.
      connection.rollback();
    }

    // A fresh borrow. If the GUC survived, this connection would answer with the
    // previous caller's tenant - and every policy would compare against it.
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("select current_setting('app.tenant_id', true)")) {
      rows.next();
      String carried = rows.getString(1);
      assertThat(carried == null || carried.isEmpty())
          .as("a pooled connection must not carry the previous transaction's tenant")
          .isTrue();
    }
  }

  private static boolean canWriteHistory(Statement statement) {
    try {
      statement.executeUpdate(
          "insert into flyway.flyway_schema_history "
              + "(installed_rank, version, description, type, script, installed_by, "
              + " execution_time, success) "
              + "values (9999, '9999', 'forged', 'SQL', 'V9999__forged.sql', "
              + " current_user, 0, true)");
      return true;
    } catch (Exception refused) {
      return false;
    }
  }
}
