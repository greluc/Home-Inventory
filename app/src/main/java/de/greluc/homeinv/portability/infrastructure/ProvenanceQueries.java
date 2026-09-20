/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.portability.infrastructure;

import de.greluc.homeinv.platform.TenantContext;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The SQL of {@code portability.import_provenance} (REQ-PORT-008).
 *
 * <p>Append-only: a second import of the same file writes a second row rather than editing the
 * first, so the table is the history the requirement asks the provenance to be visible in. Reads
 * take the most recent, which is what makes importing the same file twice an update of what it
 * produced last time rather than a second copy of everything.
 */
@Component
@RequiredArgsConstructor
public class ProvenanceQueries {

  private final JdbcClient jdbc;

  /**
   * The item a previous import gave this source key, if there was one.
   *
   * @param source which system the file came from
   * @param sourceKey what it called the row
   * @return the item, or null when this key has not been seen
   */
  @Transactional(readOnly = true)
  public UUID itemOf(String source, String sourceKey) {
    if (sourceKey == null || sourceKey.isBlank()) {
      return null;
    }
    return jdbc
        .sql(
            """
            select item_id from portability.import_provenance
            where source = ? and source_key = ?
            order by imported_at desc
            limit 1
            """)
        .param(source)
        .param(sourceKey)
        .query(UUID.class)
        .optional()
        .orElse(null);
  }

  /**
   * Records where an item came from.
   *
   * @param itemId the item
   * @param jobId the job that brought it
   * @param source which system
   * @param sourceKey what it was called there, or null
   * @param line which line of the file
   * @param actor who imported it
   */
  @Transactional
  public void record(
      UUID itemId, UUID jobId, String source, String sourceKey, int line, UUID actor) {
    jdbc.sql(
            """
            insert into portability.import_provenance
                (tenant_id, item_id, import_job_id, source, source_key, source_row, created_by)
            values (?, ?, ?, ?, ?, ?, ?)
            """)
        .param(TenantContext.require())
        .param(itemId)
        .param(jobId)
        .param(source)
        .param(sourceKey)
        .param(line)
        .param(actor)
        .update();
  }
}
