/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.infrastructure;

import de.greluc.homeinv.inventory.api.MaintenanceLog;
import de.greluc.homeinv.platform.Money;
import de.greluc.homeinv.platform.NotFoundException;
import de.greluc.homeinv.platform.TenantContext;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The maintenance log, in SQL (REQ-LIFE-003).
 *
 * <p>Three statements and no update among them: the table grants none, and the port offers none.
 * What looks like a missing feature is the requirement — an entry that could be edited afterwards
 * would make the log worth nothing as a record.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MaintenanceLogAdapter implements MaintenanceLog {

  /** The cap REQ-NFR-010 puts on every collection. */
  private static final int MAX_PAGE = 200;

  /**
   * The list, most recent work first.
   *
   * <p>Written out rather than assembled from a shared column list: `ArchitectureRulesTest` refuses
   * a SQL literal joined by {@code +} to anything, because that is the one shape through which a
   * value can enter a statement (REQ-SEC-031). Two statements repeating nine column names is the
   * cheaper half of that trade.
   */
  private static final String ENTRIES =
      """
      select id, item_id, performed_on, kind, cost_amount, cost_currency, note,
             created_at, created_by
      from inventory.maintenance_entry
      where tenant_id = ? and item_id = ?
      order by performed_on desc, created_at desc, id desc
      limit ?
      """;

  /** One entry, for the answer a recording gives back. */
  private static final String ONE_ENTRY =
      """
      select id, item_id, performed_on, kind, cost_amount, cost_currency, note,
             created_at, created_by
      from inventory.maintenance_entry
      where tenant_id = ? and id = ?
      """;

  private final JdbcClient jdbc;
  private final ItemRepository items;

  @Override
  @Transactional
  public MaintenanceEntryView record(UUID itemId, NewMaintenanceEntry entry, UUID actor) {
    UUID tenantId = TenantContext.require();
    // The item is checked here rather than left to the foreign key, so that an
    // item of another tenant is a 404 like one that never existed, instead of a
    // constraint violation that says a row is there (REQ-SEC-025).
    items.findAny(tenantId, itemId).orElseThrow(() -> new NotFoundException("item", itemId));

    UUID id =
        jdbc.sql(
                """
                insert into inventory.maintenance_entry
                    (tenant_id, item_id, performed_on, kind, cost_amount, cost_currency, note,
                     created_by)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                returning id
                """)
            .param(tenantId)
            .param(itemId)
            .param(entry.performedOn())
            .param(entry.kind().strip())
            .param(entry.cost() == null ? null : entry.cost().amount())
            .param(entry.cost() == null ? null : entry.cost().currency().getCurrencyCode())
            .param(entry.note())
            .param(actor)
            .query(UUID.class)
            .single();

    log.info("A maintenance entry was recorded for item {}", itemId);
    return byId(tenantId, id);
  }

  @Override
  @Transactional(readOnly = true)
  public List<MaintenanceEntryView> entriesOf(UUID itemId, int limit) {
    UUID tenantId = TenantContext.require();
    items.findAny(tenantId, itemId).orElseThrow(() -> new NotFoundException("item", itemId));

    return jdbc
        .sql(ENTRIES)
        .param(tenantId)
        .param(itemId)
        .param(Math.clamp(limit, 1, MAX_PAGE))
        .query(MaintenanceLogAdapter::toView)
        .list();
  }

  @Override
  @Transactional
  public void remove(UUID itemId, UUID entryId, UUID actor) {
    UUID tenantId = TenantContext.require();
    // The item in the path has to exist. Removing an ENTRY that is not there is
    // harmless -- what the caller wants is already true -- but answering 204 for
    // an item this tenant cannot see would be acting on something that is not
    // there, which REQ-SEC-025 answers with 404. The same distinction
    // `PluginRegistry.revoke` draws between a grant and a plugin.
    items.findAny(tenantId, itemId).orElseThrow(() -> new NotFoundException("item", itemId));

    int removed =
        jdbc.sql("delete from inventory.maintenance_entry where tenant_id = ? and id = ?")
            .param(tenantId)
            .param(entryId)
            .update();
    // Removing what is not there is not an error: what the caller wants is "this
    // entry is not in the log", and that is already true. Logged only when
    // something actually went, so a log line means something happened.
    if (removed > 0) {
      log.info("Maintenance entry {} was removed by {}", entryId, actor);
    }
  }

  private MaintenanceEntryView byId(UUID tenantId, UUID id) {
    return jdbc
        .sql(ONE_ENTRY)
        .param(tenantId)
        .param(id)
        .query(MaintenanceLogAdapter::toView)
        .single();
  }

  private static MaintenanceEntryView toView(ResultSet rs, int rowNum) throws SQLException {
    BigDecimal amount = rs.getBigDecimal("cost_amount");
    String currency = rs.getString("cost_currency");
    return new MaintenanceEntryView(
        rs.getObject("id", UUID.class),
        rs.getObject("item_id", UUID.class),
        rs.getObject("performed_on", java.time.LocalDate.class),
        rs.getString("kind"),
        // Both or neither: the table's own check says so, so a half-filled row
        // cannot reach here and this needs no third case.
        amount == null ? null : new Money(amount, Currency.getInstance(currency)),
        rs.getString("note"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getObject("created_by", UUID.class));
  }
}
