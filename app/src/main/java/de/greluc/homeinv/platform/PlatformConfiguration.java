/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.platform;

import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The beans the shared kernel contributes.
 *
 * <p>{@code platform} owns no schema and no domain logic (04 §4.3); what it does own is the
 * machinery every block depends on and none should configure for itself.
 */
@Configuration(proxyBeanMethods = false)
public class PlatformConfiguration {

  /**
   * The clock every use case reads the time from.
   *
   * <p>A bean rather than {@code Instant.now()} at the call site, so a test can move time without
   * sleeping and without a static mock. Every timestamp written by the application passes through
   * here.
   *
   * @return the system clock in UTC, because timestamps are stored in UTC and converted for display
   */
  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }

  /**
   * The transaction manager that publishes the tenant context to the database session.
   *
   * <p>Replaces the one Spring Boot would auto-configure. Without the replacement every policy
   * compares against a tenant that was never set, and the application returns nothing at all -
   * which is safe, and entirely useless.
   *
   * @param entityManagerFactory the factory Spring Boot configured
   * @return the transaction manager the whole application uses
   */
  @Bean
  public PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
    TenantAwareTransactionManager manager = new TenantAwareTransactionManager();
    manager.setEntityManagerFactory(entityManagerFactory);
    return manager;
  }
}
