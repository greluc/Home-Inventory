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
 *
 * <h2>The second setting</h2>
 *
 * <p>{@code app.location_scope} joins it, for the subtree a membership may be confined to
 * (REQ-TEN-007, ADR-0059). It is written on every transaction whether or not there is a scope, for
 * the same reason the tenant is: a pooled connection that kept the previous request's value would
 * run the next query under it.
 */
@Slf4j
public class TenantAwareTransactionManager extends JpaTransactionManager {

  /** Inherited from {@code JpaTransactionManager}, which is serialisable. */
  private static final long serialVersionUID = 1L;

  private static final String SET_TENANT = "select set_config('app.tenant_id', ?, true)";

  /**
   * Publishes the scope's <b>path</b>, resolved from the id the membership stores (ADR-0059).
   *
   * <p>The path and not the id, because a policy that resolved an id would resolve it per row. It
   * is resolved here, once per transaction, so a subtree that has been re-parented since the
   * session began still reads right — 07 §7.4 rewrites every path under a moved subtree, and a path
   * remembered at login would be one that is right until somebody moves the garage.
   *
   * <p>{@link #UNRESOLVABLE_SCOPE} when the id names nothing this tenant has. The empty string
   * means "no scope" and would fail <b>open</b>, which is the one outcome this must never have.
   */
  private static final String SET_SCOPE =
      "select set_config('app.location_scope',"
          + " coalesce((select place.path::text from locations.location place"
          + "           where place.tenant_id = ?::uuid and place.id = ?::uuid"
          + "             and place.deleted_at is null), ?),"
          + " true)";

  /** No scope at all: every policy's subtree condition passes. */
  private static final String NO_SCOPE = "";

  /**
   * A label no tree contains, for a scope id that resolves to nothing.
   *
   * <p>A deleted place, or one that belongs to another tenant. Writing the empty string would mean
   * "no scope", and a session confined to a place that has since been removed would be let out of
   * it rather than shut in.
   */
  private static final String UNRESOLVABLE_SCOPE = "scope_resolves_to_nothing";

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
    UUID scope =
        CallerContext.current().map(CallerContext.Caller::scopeLocationId).orElse(null);

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
              // Written on every transaction, scope or none. Skipping it would
              // leave whatever the previous transaction on this pooled connection
              // set, and the next query would run under a stale scope — the same
              // failure the tenant setting is written unconditionally to avoid.
              try (PreparedStatement statement = connection.prepareStatement(SET_SCOPE)) {
                statement.setString(1, tenant.isEmpty() ? null : tenant);
                statement.setString(2, scope == null ? null : scope.toString());
                statement.setString(3, scope == null ? NO_SCOPE : UNRESOLVABLE_SCOPE);
                statement.execute();
              }
            });

    if (log.isTraceEnabled()) {
      log.trace(
          "Transaction opened for tenant '{}', scope '{}'",
          tenant.isEmpty() ? "<none>" : tenant,
          scope == null ? "<whole tenant>" : scope);
    }
  }
}
