/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.infrastructure;

import de.greluc.homeinv.notification.api.ReminderSource;
import de.greluc.homeinv.notification.api.ReminderTrigger;
import de.greluc.homeinv.platform.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * What {@code inventory} can remind about (REQ-NOTI-003).
 *
 * <h2>The dependency points this way and only this way</h2>
 *
 * <p>{@code notification} declares {@link ReminderSource} in its published package and this block
 * implements it. So {@code inventory} depends on {@code notification.api} and {@code notification}
 * depends on nothing of ours — no cycle, and no block reading another's tables (ADR-0002,
 * REQ-NFR-019…024). The reminder run learns what is due by <b>asking</b>.
 *
 * <p>Three of REQ-NOTI-003's six triggers are served here. {@code LICENCE_EXPIRY} has no column to
 * watch and {@code STOCKTAKE_DISCREPANCY} belongs to stage 2; a rule naming either is refused when
 * it is written, rather than accepted and then never firing.
 */
@Configuration
@RequiredArgsConstructor
public class InventoryReminderSources {

  /**
   * Warranties about to run out (REQ-LIFE-002, REQ-LIFE-013).
   *
   * @param jdbc the SQL client
   * @return the source
   */
  @Bean
  public ReminderSource warrantyExpiryReminders(JdbcClient jdbc) {
    // A lifetime warranty has no date and is never due, which is exactly the
    // reason REQ-LIFE-002 made it a flag instead of a date far in the future.
    // Only things the tenant still holds: nobody wants telling that the warranty
    // on the bike they sold in March is running out.
    return new Query(
        jdbc,
        ReminderTrigger.WARRANTY_EXPIRY,
        """
        select id as subject_id, id as item_id, warranty_until as due_on, name as label
        from inventory.item
        where tenant_id = ?
          and deleted_at is null
          and lifecycle_state in ('ACTIVE', 'LENT')
          and lifetime_warranty = false
          and warranty_until is not null
          and warranty_until <= ?
        order by warranty_until
        limit ?
        """);
  }

  /**
   * Lent things that are due back, or overdue (REQ-LIFE-006).
   *
   * @param jdbc the SQL client
   * @return the source
   */
  @Bean
  public ReminderSource loanDueReminders(JdbcClient jdbc) {
    // The SUBJECT is the loan and the ITEM is what was lent: a second loan of the
    // same thing is a new thing to be reminded about, which keying on the item
    // would swallow. A loan with no agreed date is never due -- there is nothing
    // for it to be late against, and treating "no date" as "due now" would make a
    // reminder out of a lend nobody put a date on.
    return new Query(
        jdbc,
        ReminderTrigger.LOAN_DUE,
        """
        select l.id as subject_id, l.item_id as item_id, l.due_on as due_on, i.name as label
        from inventory.loan l
        join inventory.item i on i.tenant_id = l.tenant_id and i.id = l.item_id
        where l.tenant_id = ?
          and l.returned_on is null
          and l.due_on is not null
          and l.due_on <= ?
        order by l.due_on
        limit ?
        """);
  }

  /**
   * Things due for servicing (REQ-LIFE-004).
   *
   * @param jdbc the SQL client
   * @return the source
   */
  @Bean
  public ReminderSource maintenanceDueReminders(JdbcClient jdbc) {
    // MEASURED FROM THE LAST SERVICE, not from a fixed calendar: "every twelve
    // months" means twelve months since it was last done, so servicing it early
    // moves the next reminder rather than leaving it where it was.
    //
    // WHEN IT HAS NEVER BEEN SERVICED the clock starts at the purchase date, and
    // at the day the record was created when even that is unknown. Something has
    // to be the start, and "we have had it since then" is the honest one --
    // treating a never-serviced thing as never due would silently exclude
    // exactly the items somebody set an interval for.
    return new Query(
        jdbc,
        ReminderTrigger.MAINTENANCE_DUE,
        """
        select i.id as subject_id, i.id as item_id, i.name as label,
               coalesce(
                   (select max(m.performed_on) from inventory.maintenance_entry m
                     where m.tenant_id = i.tenant_id and m.item_id = i.id),
                   i.purchased_on,
                   cast(i.created_at as date)
               ) + i.maintenance_interval_days as due_on
        from inventory.item i
        where i.tenant_id = ?
          and i.deleted_at is null
          and i.lifecycle_state in ('ACTIVE', 'LENT')
          and i.maintenance_interval_days is not null
          and coalesce(
                  (select max(m.performed_on) from inventory.maintenance_entry m
                    where m.tenant_id = i.tenant_id and m.item_id = i.id),
                  i.purchased_on,
                  cast(i.created_at as date)
              ) + i.maintenance_interval_days <= ?
        order by due_on
        limit ?
        """);
  }

  /**
   * Consumables that have run down (REQ-CORE-016).
   *
   * @param jdbc the SQL client
   * @return the source
   */
  @Bean
  public ReminderSource minimumStockReminders(JdbcClient jdbc) {
    // THE ONE TRIGGER WITH NO DATE. The condition is `quantity <= minimum_stock`,
    // so the horizon the runner passes is ignored by the predicate and used only
    // as the `due_on` recorded against the reminder -- which is what makes the
    // reminder repeat if the coffee is still low tomorrow and not twice today.
    return new Query(
        jdbc,
        ReminderTrigger.MINIMUM_STOCK,
        """
        select id as subject_id, id as item_id, cast(? as date) as due_on, name as label
        from inventory.item
        where tenant_id = ?
          and deleted_at is null
          and lifecycle_state in ('ACTIVE', 'LENT')
          and minimum_stock is not null
          and quantity <= minimum_stock
        order by name
        limit ?
        """,
        true);
  }

  /**
   * A source that is one query.
   *
   * <p>All three are the same shape — a tenant, a horizon and a limit — so they are one class with
   * three statements rather than three classes differing by a string. Each statement is a full
   * literal: {@code ArchitectureRulesTest} refuses a SQL literal joined by {@code +} to anything,
   * because that is the one shape through which a value can enter a statement (REQ-SEC-031).
   */
  @RequiredArgsConstructor
  private static final class Query implements ReminderSource {

    private final JdbcClient jdbc;
    private final ReminderTrigger trigger;
    private final String sql;

    /** Whether the horizon is the first parameter rather than the second — see the stock query. */
    private final boolean horizonFirst;

    Query(JdbcClient jdbc, ReminderTrigger trigger, String sql) {
      this(jdbc, trigger, sql, false);
    }

    @Override
    public ReminderTrigger trigger() {
      return trigger;
    }

    // NO `@Transactional` here, and not by omission. The runner calls this from
    // inside its own transaction, which is where `SET LOCAL app.tenant_id` was
    // applied, so a read here is already scoped -- and an annotation on a final
    // class cannot be proxied at all, which is how this was found.
    @Override
    public List<Due> dueBy(LocalDate by, int limit) {
      UUID tenantId = TenantContext.require();
      JdbcClient.StatementSpec statement = jdbc.sql(sql);
      // Read under the ordinary row-level security: the run has already entered
      // the tenant's context, so this sees that tenant's rows and no others.
      statement =
          horizonFirst
              ? statement.param(by).param(tenantId)
              : statement.param(tenantId).param(by);
      return statement.param(limit).query(Query::toDue).list();
    }

    private static Due toDue(ResultSet rs, int rowNum) throws SQLException {
      return new Due(
          rs.getObject("subject_id", UUID.class),
          rs.getObject("item_id", UUID.class),
          rs.getObject("due_on", LocalDate.class),
          rs.getString("label"));
    }
  }
}
