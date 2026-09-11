/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.platform;

import jakarta.persistence.EntityManager;
import java.sql.PreparedStatement;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Session;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Sets {@code app.tenant_id} on the database session at the start of every transaction, so the
 * row-level security policies of {@code 07 §7.5} have a tenant to compare against.
 *
 * <p>This is the join between the application's notion of a tenant and the database's. Without it
 * every policy evaluates against {@code NULL} and every query returns nothing — which is the
 * correct failure direction, but it means a missing tenant context shows up as an empty result
 * rather than as data belonging to somebody else.
 *
 * <h2>Why {@code set_config} and not {@code SET LOCAL}</h2>
 *
 * <p>{@code SET LOCAL app.tenant_id = ?} does not exist: {@code SET} takes a literal, not a bind
 * parameter. Writing it anyway means building the statement by string concatenation, which puts a
 * value derived from user data into SQL text — in the one place whose entire purpose is to keep
 * tenants apart. {@code select set_config('app.tenant_id', ?, true)} is the parameterised
 * equivalent, and the {@code true} makes it transaction-local exactly as {@code SET LOCAL} would.
 *
 * <p>The value is a {@link UUID} that came from the authenticated principal, so it could not carry
 * SQL in practice. It is bound rather than interpolated anyway, because "could not in practice" is
 * an argument that survives exactly until someone changes where the value comes from.
 *
 * <h2>Why the transaction manager and not a filter</h2>
 *
 * <p>A transaction-local setting needs a transaction to be local to. Setting it when the connection
 * is borrowed would attach it to the implicit transaction of that statement and lose it
 * immediately; setting it in a servlet filter would not reach the connection at all. {@code
 * doBegin} is the first moment at which a connection and a transaction both exist.
 *
 * <p>Only new transactions pass through here. One joining an existing transaction reuses its
 * connection, where the value is already set — and a nested transaction that wanted a *different*
 * tenant would be a bug this class should not quietly enable.
 */
@Slf4j
public class TenantAwareTransactionManager extends JpaTransactionManager {

  /** Inherited from {@code JpaTransactionManager}, which is serialisable. */
  private static final long serialVersionUID = 1L;

  private static final String SET_TENANT = "select set_config('app.tenant_id', ?, true)";

  /** Creates a transaction manager that publishes the tenant context to the database session. */
  public TenantAwareTransactionManager() {
    super();
  }

  /**
   * Begins the transaction and publishes the current tenant to the database session.
   *
   * <p>When no tenant is established the setting is written as the empty string rather than being
   * skipped. Skipping it would leave whatever the previous transaction on this pooled connection
   * set, and the next query would run under a stale tenant — the exact failure the pooling note in
   * {@code 07 §7.5} describes. The empty string is what the policies' {@code nullif} turns into
   * {@code NULL}, which yields zero rows.
   *
   * @param transaction the transaction object, as handed over by Spring
   * @param definition the definition of the transaction being started
   */
  @Override
  protected void doBegin(Object transaction, TransactionDefinition definition) {
    super.doBegin(transaction, definition);

    String tenant = TenantContext.current().map(UUID::toString).orElse("");

    EntityManagerHolder holder =
        (EntityManagerHolder) TransactionSynchronizationManager.getResource(obtainEntityManagerFactory());
    if (holder == null) {
      // Cannot happen after a successful doBegin; if it ever does, failing here
      // is the only safe outcome. Continuing would run the transaction with no
      // tenant setting at all.
      throw new IllegalStateException(
          "No EntityManager bound after beginning the transaction — the tenant context "
              + "cannot be published and the transaction must not proceed.");
    }

    EntityManager entityManager = holder.getEntityManager();
    entityManager
        .unwrap(Session.class)
        .doWork(
            connection -> {
              try (PreparedStatement statement = connection.prepareStatement(SET_TENANT)) {
                statement.setString(1, tenant);
                statement.execute();
              }
            });

    if (log.isTraceEnabled()) {
      log.trace("Transaction opened for tenant '{}'", tenant.isEmpty() ? "<none>" : tenant);
    }
  }
}
