/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.platform;

import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The {@code migrate} role: apply every pending migration, report, exit.
 *
 * <h2>Why this is a service of its own</h2>
 *
 * <p>It connects as {@code homeinv_migrator}, the one role that owns the tables and can rewrite an
 * RLS policy. Migrating from inside {@code api} would mean that credential lived in a long-running,
 * internet-facing process for that process's whole life, which undoes part of what ADR-0003 buys.
 * This runs, exits, and its credential is mounted nowhere else (ADR-0041, REQ-SEC-101).
 *
 * <h2>Why Flyway is constructed here rather than auto-configured</h2>
 *
 * <p>Because Spring Boot's Flyway auto-configuration is bound to {@code spring.flyway.*}, and that
 * is switched off for every other role. Turning it on per profile would leave the application one
 * misplaced property away from migrating itself, which is precisely the arrangement this design
 * removes. Building it here means the ability to migrate exists only where this bean does.
 *
 * <h2>The exit code is the contract</h2>
 *
 * <p>The container is {@code Type=oneshot} under Quadlet and {@code service_completed_successfully}
 * under Compose, and both read the exit status. A failure must therefore end the process non-zero
 * rather than be logged and shrugged off: on failure {@code api} and {@code worker} must not start
 * (06 §6.12).
 */
@Slf4j
@Component
@Profile("migrate")
@RequiredArgsConstructor
public class MigrationRunner implements ApplicationRunner {

  private final DataSource dataSource;
  private final ConfigurableApplicationContext context;

  /** Deliberately an explicit switch, so the role and the action are not the same fact. */
  @Value("${HOMEINV_DB_MIGRATE_ON_START:true}")
  private boolean migrateOnStart;

  @Override
  public void run(ApplicationArguments args) {
    if (!migrateOnStart) {
      log.info("HOMEINV_DB_MIGRATE_ON_START is false; nothing to do.");
      exit(0);
      return;
    }

    Flyway flyway =
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .schemas("flyway")
            .defaultSchema("flyway")
            // Every migration this build ships must already be applied or be
            // applicable. Out-of-order would let a lower version land after a
            // higher one, and the version comparison SchemaVersionCheck makes
            // would then be answering a different question than it appears to.
            .outOfOrder(false)
            // A changed checksum means a migration that has already run was
            // edited. Repairing it silently is how two installations end up with
            // different schemas and the same version number.
            .validateOnMigrate(true)
            .load();

    try {
      MigrateResult result = flyway.migrate();
      if (result.migrationsExecuted == 0) {
        log.info("Schema already at version {}; nothing to apply.", result.targetSchemaVersion);
      } else {
        log.info(
            "Applied {} migration(s); schema is now at version {}.",
            result.migrationsExecuted,
            result.targetSchemaVersion);
      }
      exit(0);
    } catch (RuntimeException failed) {
      // Logged here and rethrown nowhere: the message is what an operator reads,
      // and the exit code is what the runtime acts on.
      log.error("Migration failed. api and worker will not start.", failed);
      exit(1);
    }
  }

  private void exit(int code) {
    System.exit(SpringApplication.exit(context, () -> code));
  }
}
