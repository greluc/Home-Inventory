/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.search.infrastructure;

import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.search.api.SavedSearches.SavedSearchView;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The statements behind {@code search.saved_search} (REQ-SRCH-008).
 *
 * <p>JDBC and not JPA, like the other read-shaped parts of this block: the table has four columns
 * of content and one of them is a {@code text[]}, which an entity would map through a converter for
 * no benefit. Every statement is parameterised and none of them is assembled from input.
 *
 * <p>Row-level security is the second line as always (ADR-0003): every statement also names
 * {@code tenant_id}, so a missing context is zero rows twice over rather than once.
 */
@Component
@RequiredArgsConstructor
public class SavedSearchQueries {

  private final JdbcClient jdbc;

  /**
   * One page of the tenant's saved searches, oldest first.
   *
   * @param after the creation instant to resume after, or {@code null} for the first page
   * @param afterId the id to resume after, breaking a tie on the instant
   * @param limit how many at most
   * @return the rows
   */
  @Transactional(readOnly = true)
  public List<SavedSearchView> list(Instant after, UUID afterId, int limit) {
    UUID tenantId = TenantContext.require();
    if (after == null) {
      return jdbc
          .sql(
              """
              select id, name, query_text, filters, sort, created_at, updated_at, version
              from search.saved_search
              where tenant_id = ?
              order by created_at, id
              limit ?
              """)
          .params(tenantId, limit)
          .query(SavedSearchQueries::toView)
          .list();
    }
    return jdbc
        .sql(
            """
            select id, name, query_text, filters, sort, created_at, updated_at, version
            from search.saved_search
            where tenant_id = ?
              and (created_at, id) > (?, ?)
            order by created_at, id
            limit ?
            """)
        .params(tenantId, after.atOffset(java.time.ZoneOffset.UTC), afterId, limit)
        .query(SavedSearchQueries::toView)
        .list();
  }

  /**
   * One saved search.
   *
   * @param id which one
   * @return it, or empty when the tenant has none with that id
   */
  @Transactional(readOnly = true)
  public Optional<SavedSearchView> byId(UUID id) {
    return jdbc
        .sql(
            """
            select id, name, query_text, filters, sort, created_at, updated_at, version
            from search.saved_search
            where tenant_id = ? and id = ?
            """)
        .params(TenantContext.require(), id)
        .query(SavedSearchQueries::toView)
        .optional();
  }

  /**
   * Whether the tenant already has one under this name, ignoring one id.
   *
   * <p>Asked before the insert so that the answer is a named conflict rather than a constraint
   * violation nobody can read. The unique index is still what makes it true: two requests racing
   * each other both pass this check and the second one fails at the index, which is the correct
   * outcome and the reason the index exists.
   *
   * @param name the name to check, compared without regard to case
   * @param exceptId a saved search to ignore, for a rename that keeps its own name
   * @return true when the name is in use by another
   */
  @Transactional(readOnly = true)
  public boolean nameTaken(String name, UUID exceptId) {
    return !jdbc
        .sql(
            """
            select 1
            from search.saved_search
            where tenant_id = ? and lower(name) = lower(?) and (?::uuid is null or id <> ?)
            """)
        .params(TenantContext.require(), name, exceptId, exceptId)
        .query(Integer.class)
        .list()
        .isEmpty();
  }

  /**
   * Writes a new saved search.
   *
   * @param id the identifier to give it
   * @param name what to call it
   * @param text the query text, or {@code null}
   * @param filters the filter parameters verbatim
   * @param sort the sort key, or {@code null}
   * @param actor who saved it
   */
  @Transactional
  public void insert(
      UUID id, String name, String text, List<String> filters, String sort, UUID actor) {
    jdbc.sql(
            """
            insert into search.saved_search
                (id, tenant_id, name, query_text, filters, sort, created_by, updated_by)
            values (?, ?, ?, ?, ?, ?, ?, ?)
            """)
        .params(
            id,
            TenantContext.require(),
            name,
            text,
            filters.toArray(String[]::new),
            sort,
            actor,
            actor)
        .update();
  }

  /**
   * Overwrites a saved search and bumps its version.
   *
   * @param id which one
   * @param name the new name
   * @param text the new query text, or {@code null}
   * @param filters the new filter parameters
   * @param sort the new sort key, or {@code null}
   * @param actor who changed it
   * @return how many rows changed, which is one or none
   */
  @Transactional
  public int update(
      UUID id, String name, String text, List<String> filters, String sort, UUID actor) {
    return jdbc
        .sql(
            """
            update search.saved_search
            set name = ?, query_text = ?, filters = ?, sort = ?,
                updated_at = now(), updated_by = ?, version = version + 1
            where tenant_id = ? and id = ?
            """)
        .params(
            name,
            text,
            filters.toArray(String[]::new),
            sort,
            actor,
            TenantContext.require(),
            id)
        .update();
  }

  /**
   * Removes a saved search.
   *
   * @param id which one
   * @return how many rows went, which is one or none
   */
  @Transactional
  public int delete(UUID id) {
    return jdbc
        .sql("delete from search.saved_search where tenant_id = ? and id = ?")
        .params(TenantContext.require(), id)
        .update();
  }

  /**
   * Reads one row.
   *
   * @param rs the result set, positioned on the row
   * @param rowNum which row, unused
   * @return the view
   * @throws SQLException when a column cannot be read
   */
  private static SavedSearchView toView(ResultSet rs, int rowNum) throws SQLException {
    Array filters = rs.getArray("filters");
    List<String> parameters =
        filters == null ? List.of() : List.of((String[]) filters.getArray());
    return new SavedSearchView(
        rs.getObject("id", UUID.class),
        rs.getString("name"),
        rs.getString("query_text"),
        parameters,
        rs.getString("sort"),
        // OffsetDateTime, not Instant: the PostgreSQL driver refuses a direct
        // conversion from timestamptz to Instant, and says so at runtime rather
        // than at compile time.
        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
        rs.getLong("version"));
  }
}
