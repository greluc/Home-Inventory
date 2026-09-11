/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.platform;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Compares the schema this build expects against the one the database has.
 *
 * <h2>Why the application checks rather than migrates</h2>
 *
 * <p>The application connects only as {@code homeinv_app}, which has no DDL rights. Migrating from
 * inside a long-running, internet-facing process would mean mounting {@code homeinv_migrator} — the
 * one role that can rewrite an RLS policy — for that process's whole life (ADR-0041, REQ-SEC-101).
 * The one-shot {@code migrate} service does the work and exits; this class establishes that it did.
 *
 * <h2>What "expected" means</h2>
 *
 * <p>The highest version among the migrations <em>this build ships</em>. Nothing is hardcoded, so
 * there is no number to forget to update: adding {@code V11__…sql} raises the expectation by
 * existing.
 *
 * <h2>Behind and ahead are not the same failure</h2>
 *
 * <ul>
 *   <li><b>Behind</b> — the database lacks a migration this build needs. Startup fails. Without
 *       this the omission surfaces later as a missing column, at request time, in production.
 *   <li><b>Ahead</b> — the database has migrations this build does not know about. Startup
 *       continues with a warning, because that is the expected state of an <em>old</em> instance
 *       during a rolling upgrade (06 §6.12: migrate runs first). Refusing here would turn every
 *       upgrade into an outage.
 * </ul>
 */
@Slf4j
@Component
public class SchemaVersion {

  /** Flyway's own history table. The application may read it and nothing else in that schema. */
  private static final String LATEST_APPLIED =
      """
      select version from flyway.flyway_schema_history
       where success = true and version is not null
       order by installed_rank desc
       limit 1
      """;

  /** {@code V9__media_object_and_attachment.sql} → {@code 9}. */
  private static final Pattern VERSIONED = Pattern.compile("/V(\\d+)__[^/]+\\.sql$");

  private final DataSource dataSource;
  private final int expected;

  /**
   * Reads the expectation from the migrations on the classpath.
   *
   * @param dataSource the application's own connection pool, as {@code homeinv_app}
   * @throws IllegalStateException when no migration is on the classpath at all, which means the jar
   *     was built without its resources and every schema check below would pass vacuously
   */
  public SchemaVersion(DataSource dataSource) {
    this.dataSource = dataSource;
    this.expected = highestShipped();
  }

  /**
   * The highest migration version this build carries.
   *
   * @return the version number
   */
  public int expected() {
    return expected;
  }

  /**
   * The highest migration version the database has successfully applied.
   *
   * @return the version, or 0 when the history is empty or unreadable
   */
  public int applied() {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(LATEST_APPLIED);
        ResultSet rows = statement.executeQuery()) {
      if (!rows.next()) {
        return 0;
      }
      // Flyway stores the version as text ("9", and "1.1" if anyone ever uses a
      // minor). Only the major part is compared, because that is what the file
      // names in this project carry.
      String version = rows.getString(1);
      int dot = version.indexOf('.');
      return Integer.parseInt(dot < 0 ? version : version.substring(0, dot));
    } catch (SQLException | NumberFormatException unreadable) {
      log.warn("The Flyway history could not be read: {}", unreadable.getMessage());
      return 0;
    }
  }

  /**
   * Whether the database carries every migration this build needs.
   *
   * @return true when the applied version is at least the expected one
   */
  public boolean satisfied() {
    return applied() >= expected;
  }

  private static int highestShipped() {
    try {
      Resource[] migrations =
          new PathMatchingResourcePatternResolver()
              .getResources("classpath*:db/migration/**/V*__*.sql");
      int highest =
          java.util.Arrays.stream(migrations)
              .map(SchemaVersion::versionOf)
              .filter(version -> version > 0)
              .max(Comparator.naturalOrder())
              .orElse(0);
      if (highest == 0) {
        throw new IllegalStateException(
            "No Flyway migration is on the classpath. Either the build dropped its resources or "
                + "the locations moved; either way every schema check would pass for the wrong "
                + "reason.");
      }
      return highest;
    } catch (IOException unreadable) {
      throw new IllegalStateException("The migrations on the classpath could not be listed", unreadable);
    }
  }

  private static int versionOf(Resource migration) {
    try {
      Matcher matcher = VERSIONED.matcher(migration.getURL().getPath());
      return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    } catch (IOException unreadable) {
      return 0;
    }
  }
}
