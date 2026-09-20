/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.infrastructure;

import de.greluc.homeinv.catalog.api.TypeRegistry;
import de.greluc.homeinv.notification.api.ReminderSource;
import de.greluc.homeinv.notification.api.ReminderTrigger;
import de.greluc.homeinv.platform.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Things whose licence, certificate or inspection runs out (REQ-NOTI-003, REQ-LIFE-013,
 * REQ-CORE-023).
 *
 * <h2>The date it was waiting for</h2>
 *
 * <p>{@code LICENCE_EXPIRY} was declared and served by nothing, for a reason that was true when it
 * was written: nothing stored a licence expiry date, and a trigger reading a column that does not
 * exist is a rule that never fires. The {@code expiry} flag on a field definition is that column —
 * a tenant marks a date field as an expiry, and every item written against a type that has one
 * carries a date this can watch.
 *
 * <p>That makes it general rather than about licences: a tenant marks a passport, an inspection, a
 * certificate or a tin of paint, and this watches all of them. The trigger keeps its name because
 * REQ-NOTI-003 names it, and a rule already written against it would otherwise stop meaning
 * anything.
 *
 * <h2>Read from the index, which is allowed to be behind</h2>
 *
 * <p>{@code item_attr_index} is derived (ADR-0004) and is the only place an attribute is a
 * <b>date</b> rather than a string in a JSONB object. A reminder that is a day late because the
 * index was rebuilding is a reminder; a query that read the JSONB and compared text would be a
 * reminder that is wrong about leap years. The projection is maintained in the same transaction as
 * the write, so behind means microseconds.
 */
@Component
@RequiredArgsConstructor
public class ExpiryFieldReminders implements ReminderSource {

  /**
   * Items with an expiry-flagged attribute falling due.
   *
   * <p>{@code field_key = any(?)} rather than a built-in list: the keys come from {@code
   * field_definition} through {@code catalog}'s own port, which is REQ-SEC-031's allowlist, and
   * they arrive as one array parameter rather than as text in a statement.
   */
  private static final String DUE =
      """
      select a.item_id as subject_id, a.item_id as item_id,
             a.date_value::date as due_on, i.name as label
      from inventory.item_attr_index a
      join inventory.item i on i.tenant_id = a.tenant_id and i.id = a.item_id
      where a.tenant_id = ?
        and a.field_key = any(?)
        and a.date_value is not null
        and a.date_value::date <= ?
        and i.deleted_at is null
        and i.lifecycle_state in ('ACTIVE', 'LENT')
      order by a.date_value
      limit ?
      """;

  private final JdbcClient jdbc;
  private final TypeRegistry types;

  @Override
  public ReminderTrigger trigger() {
    return ReminderTrigger.LICENCE_EXPIRY;
  }

  // No `@Transactional`, and not by omission: the runner calls this from inside
  // its own transaction, which is where `SET LOCAL app.tenant_id` was applied.
  @Override
  public List<Due> dueBy(LocalDate by, int limit) {
    List<String> keys = types.expiryFields().stream().map(TypeRegistry.ExpiryField::key).toList();
    if (keys.isEmpty()) {
      // No type in this tenant marks a field as an expiry, so there is nothing
      // to watch. Returning early rather than running a query with an empty
      // array, which PostgreSQL answers correctly and slowly.
      return List.of();
    }
    return jdbc
        .sql(DUE)
        .param(TenantContext.require())
        .param(keys.toArray(String[]::new))
        .param(by)
        .param(limit)
        .query(ExpiryFieldReminders::toDue)
        .list();
  }

  /**
   * One row as something due.
   *
   * @param rs the row
   * @param rowNum which row
   * @return what is due
   * @throws SQLException when it cannot be read
   */
  private static Due toDue(ResultSet rs, int rowNum) throws SQLException {
    return new Due(
        rs.getObject("subject_id", UUID.class),
        rs.getObject("item_id", UUID.class),
        rs.getObject("due_on", LocalDate.class),
        rs.getString("label"));
  }
}
