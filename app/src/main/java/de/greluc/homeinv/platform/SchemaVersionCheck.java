/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.platform;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Refuses to serve against a schema this build does not expect.
 *
 * <p>Two mechanisms, because they answer different questions at different moments.
 *
 * <p><b>At startup</b> the check aborts the context. A rollout that skipped its migration stops
 * here, loudly, instead of continuing and producing a missing-column error on whichever request
 * first touches the new table (ADR-0041, REQ-SEC-101, 05 §5.11).
 *
 * <p><b>On every readiness probe</b> the same comparison runs again as the {@code schemaVersion}
 * health indicator, which {@code /readyz} includes. A running instance can fall behind without
 * restarting — somebody applies a migration while it serves — and readiness is the signal that
 * takes it out of rotation.
 *
 * <p>Not active in the {@code migrate} profile: that role's whole job is to change the version this
 * class compares against, and it necessarily starts against the older one.
 */
@Slf4j
@Component
@Profile("!migrate")
@RequiredArgsConstructor
public class SchemaVersionCheck implements HealthIndicator, InitializingBean {

  private final SchemaVersion schema;

  /**
   * Compares the versions and aborts when the database is behind.
   *
   * @throws IllegalStateException when a migration this build needs has not been applied
   */
  @Override
  public void afterPropertiesSet() {
    int expected = schema.expected();
    int applied = schema.applied();

    if (applied < expected) {
      throw new IllegalStateException(
          ("This build expects database schema version %d and the database is at %d. "
                  + "The one-shot `migrate` service has not run, or it failed. "
                  + "Starting anyway would turn a skipped migration into a missing column on "
                  + "whichever request first needs it (ADR-0041, REQ-SEC-101).")
              .formatted(expected, applied));
    }
    if (applied > expected) {
      log.warn(
          "The database is at schema version {} and this build expects {}. That is the normal "
              + "state of an instance still serving during a rolling upgrade; if it is not an "
              + "upgrade, this instance is older than the schema it is serving.",
          applied,
          expected);
    } else {
      log.info("Database schema version {} matches this build.", applied);
    }
  }

  /**
   * The readiness view of the same comparison.
   *
   * @return {@code UP} when the database carries every migration this build needs
   */
  @Override
  public Health health() {
    int expected = schema.expected();
    int applied = schema.applied();
    Health.Builder builder = applied >= expected ? Health.up() : Health.down();
    return builder.withDetail("expected", expected).withDetail("applied", applied).build();
  }
}
