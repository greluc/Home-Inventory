/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.search.infrastructure;

import de.greluc.homeinv.inventory.api.ItemSearchQuery;
import de.greluc.homeinv.search.api.SearchIndex;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * PostgreSQL behind the {@link SearchIndex} port (ADR-0008, REQ-SRCH-005).
 *
 * <p>The shipped fallback, and the only search the {@code minimal} profile has — which is why it is
 * not a stub. Its full-text matching is the two generated {@code tsvector} columns on
 * {@code inventory.item}, one per shipped language (ADR-0047), and its paging is keyset rather than
 * offset (REQ-SRCH-009).
 *
 * <h2>It is thin on purpose</h2>
 *
 * <p>The statement itself lives in {@code inventory}, behind {@code ItemSearchQuery}: no block reads
 * another block's schema (REQ-NFR-021), and the table belongs to {@code inventory} whoever is
 * asking. What this class adds is the shape of the port — which is almost nothing, and that is the
 * point. When the OpenSearch adapter arrives beside it, the difference between the two will be the
 * engine and not the contract.
 *
 * <h2>Always available</h2>
 *
 * <p>{@link #available()} is {@code true} without asking. The database is where the items are: if it
 * is unreachable the request has already failed for reasons no search fallback could mend, and a
 * health check here would be a query asking whether queries work.
 */
@Component
@RequiredArgsConstructor
public class PostgresSearchIndex implements SearchIndex {

  private final ItemSearchQuery items;

  /**
   * What this engine is called.
   *
   * @return {@code postgresql}
   */
  @Override
  public String name() {
    return "postgresql";
  }

  /**
   * Whether this index can answer, which it always can.
   *
   * @return {@code true}
   */
  @Override
  public boolean available() {
    return true;
  }

  /**
   * Finds the matching items, oldest first.
   *
   * <p>Relevance ranking is OpenSearch's advantage and this adapter does not pretend to it: the
   * order is the keyset order, which is stable and resumable. `degraded-reasons.yaml` says so in as
   * many words — under the fallback "the ranking is poorer", and the hits are still correct.
   *
   * @param query what to look for
   * @return the ids and where the next page resumes
   */
  @Override
  public Hits find(Query query) {
    ItemSearchQuery.Rows rows =
        items.search(
            query.text(),
            query.language(),
            query.locationIds(),
            query.after(),
            query.sort(),
            query.filters(),
            query.limit());
    return new Hits(rows.ids(), rows.last());
  }
}
