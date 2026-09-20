/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.notification.infrastructure;

import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.portability.api.ImportSql;
import de.greluc.homeinv.portability.api.ImportTarget;
import de.greluc.homeinv.portability.api.Remapping;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads reminder rules back out of an archive (REQ-PORT-003, REQ-PORT-006).
 *
 * <p>Last, at {@link #order()} 60, because a rule may point at a saved search — warn me about
 * everything this search finds — and the searches arrive at 50.
 *
 * <h2>Rules travel; channels do not</h2>
 *
 * <p>{@code notification.subscription} carries an <b>address</b> and belongs to an account rather
 * than to the inventory. An import writes no accounts, so a subscription would point at somebody
 * who is not here and would arrange for this instance to send mail to an address out of an uploaded
 * file. The archive carries them so that a person can read what was set up; the import leaves them
 * alone and counts them as skipped, which is what puts them in the report.
 */
@Component
@RequiredArgsConstructor
public class NotificationImport implements ImportTarget {

  private static final List<String> RULE =
      List.of(
          "id", "name", "trigger_kind", "saved_search_id", "offset_days", "channel_key", "enabled",
          "created_at", "updated_at", "created_by", "updated_by", "version");

  private final JdbcClient jdbc;
  private final ObjectMapper json;

  @Override
  public String block() {
    return "notification";
  }

  @Override
  public int order() {
    return 60;
  }

  @Override
  public Outcome importFrom(Archive archive, Remapping ids) {
    int inserted = 0;
    int overwritten = 0;
    for (Map<String, Object> row : archive.rows("notification", "reminder-rules")) {
      boolean fresh =
          Boolean.TRUE.equals(
              jdbc.sql(ImportSql.upsert("notification.notification_rule", RULE, "tenant_id, id"))
                  .param(TenantContext.require())
                  .param(ImportSql.asJson(json, row, Set.of()))
                  .query(Boolean.class)
                  .single());
      if (fresh) {
        inserted++;
      } else {
        overwritten++;
      }
    }
    return new Outcome(inserted, overwritten, archive.rows("notification", "subscriptions").size());
  }
}
