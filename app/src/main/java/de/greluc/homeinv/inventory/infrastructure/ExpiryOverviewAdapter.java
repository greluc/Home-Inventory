/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.infrastructure;

import de.greluc.homeinv.catalog.api.TypeRegistry;
import de.greluc.homeinv.inventory.api.ExpiryOverview;
import de.greluc.homeinv.platform.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Everything that runs out, in one list (REQ-LIFE-013).
 *
 * <h2>Two sources, one statement</h2>
 *
 * <p>A {@code UNION ALL} over the warranty column and the attribute index, sorted together. Two
 * queries merged in Java would have to page twice and sort the join of two pages, which gives the
 * wrong answer whenever one side is longer than the page — the database sorts the whole thing and
 * takes the first {@code n}.
 *
 * <p>Which attribute keys count comes from {@code catalog} through its published port, never from a
 * join into its schema (REQ-NFR-021). The values are this block's own, in {@code item_attr_index}.
 */
@Component
@RequiredArgsConstructor
public class ExpiryOverviewAdapter implements ExpiryOverview {

  /** The cap REQ-NFR-010 puts on every collection. */
  private static final int MAX_ROWS = 200;

  /**
   * Warranties and marked attribute dates, soonest first.
   *
   * <p>Written out in full rather than assembled: {@code ArchitectureRulesTest} refuses a SQL
   * literal joined by {@code +} to anything (REQ-SEC-031). The two date bounds are parameters that
   * are always bound — {@code :upTo} as a far-future date when the caller named none, {@code
   * :from} as a far-past one when the caller wants the ones already gone — so one statement serves
   * every combination and there is no branch that builds a different one.
   */
  private static final String DUE =
      """
      select i.id as item_id, i.name as item_name, 'warranty' as kind, i.warranty_until as due_on
        from inventory.item i
       where i.tenant_id = :tenant
         and i.deleted_at is null
         and i.lifecycle_state in ('ACTIVE', 'LENT', 'ARCHIVED')
         and i.lifetime_warranty = false
         and i.warranty_until is not null
         and i.warranty_until <= :upTo
         and i.warranty_until >= :from
      union all
      select i.id, i.name, a.field_key, cast(a.date_value as date)
        from inventory.item i
        join inventory.item_attr_index a
          on a.tenant_id = i.tenant_id and a.item_id = i.id
       where i.tenant_id = :tenant
         and i.deleted_at is null
         and i.lifecycle_state in ('ACTIVE', 'LENT', 'ARCHIVED')
         and a.field_key in (:keys)
         and a.date_value is not null
         and cast(a.date_value as date) <= :upTo
         and cast(a.date_value as date) >= :from
       order by due_on, item_name
       limit :limit
      """;

  /** Only warranties, for the tenant whose types mark no expiry attribute at all. */
  private static final String DUE_WARRANTIES_ONLY =
      """
      select i.id as item_id, i.name as item_name, 'warranty' as kind, i.warranty_until as due_on
        from inventory.item i
       where i.tenant_id = :tenant
         and i.deleted_at is null
         and i.lifecycle_state in ('ACTIVE', 'LENT', 'ARCHIVED')
         and i.lifetime_warranty = false
         and i.warranty_until is not null
         and i.warranty_until <= :upTo
         and i.warranty_until >= :from
       order by due_on, item_name
       limit :limit
      """;

  /** The bound used when the caller named none. Far enough out to mean "everything". */
  private static final LocalDate FOREVER = LocalDate.of(9999, 12, 31);

  /** The lower bound used when the caller wants what has already run out. */
  private static final LocalDate ALWAYS = LocalDate.of(1, 1, 1);

  private final JdbcClient jdbc;
  private final TypeRegistry types;

  @Override
  @Transactional(readOnly = true)
  public List<Expiring> due(LocalDate upTo, boolean includePast, int limit) {
    UUID tenantId = TenantContext.require();
    List<TypeRegistry.ExpiryField> fields = types.expiryFields();
    Map<String, Map<String, String>> labels = new java.util.HashMap<>();
    fields.forEach(field -> labels.put(field.key(), field.labels()));

    LocalDate ceiling = upTo == null ? FOREVER : upTo;
    LocalDate floor = includePast ? ALWAYS : LocalDate.now();
    int rows = Math.clamp(limit, 1, MAX_ROWS);

    // `in (:keys)` with an empty list is not valid SQL, and a tenant whose types
    // mark no expiry attribute is the ordinary case rather than an edge: the
    // warranty half still has something to say.
    JdbcClient.StatementSpec statement =
        fields.isEmpty()
            ? jdbc.sql(DUE_WARRANTIES_ONLY)
            : jdbc.sql(DUE).param("keys", fields.stream().map(TypeRegistry.ExpiryField::key).toList());

    List<Expiring> found = new ArrayList<>();
    statement
        .param("tenant", tenantId)
        .param("upTo", ceiling)
        .param("from", floor)
        .param("limit", rows)
        .query((ResultSet rs, int row) -> found.add(toExpiring(rs, labels)))
        .list();
    return List.copyOf(found);
  }

  /**
   * One row, with the kind given a name somebody can read.
   *
   * @param rs the row
   * @param labels the labels each expiry field declares, by key
   * @return the entry
   * @throws SQLException when the row cannot be read
   */
  private static Expiring toExpiring(ResultSet rs, Map<String, Map<String, String>> labels)
      throws SQLException {
    String kind = rs.getString("kind");
    return new Expiring(
        rs.getObject("item_id", UUID.class),
        rs.getString("item_name"),
        kind,
        labelFor(kind, labels),
        rs.getObject("due_on", LocalDate.class));
  }

  /**
   * What to call a kind.
   *
   * <p>The field's own label in the caller's language, falling back to English and then to the key
   * itself. A key is a poor label and it is better than an empty cell, which is what a person sees
   * when a tenant defines a field and gives it no label at all.
   *
   * @param kind {@code warranty}, or an attribute key
   * @param labels the labels each expiry field declares
   * @return a label
   */
  private static String labelFor(String kind, Map<String, Map<String, String>> labels) {
    if ("warranty".equals(kind)) {
      return "Warranty";
    }
    Map<String, String> declared = labels.getOrDefault(kind, Map.of());
    String language = requestLanguage();
    String label = declared.get(language);
    if (label == null) {
      label = declared.get("en");
    }
    return label == null ? kind : label;
  }

  /**
   * The language the request is being answered in.
   *
   * @return the IETF language tag, lower-cased
   */
  private static String requestLanguage() {
    return org.springframework.context.i18n.LocaleContextHolder.getLocale()
        .getLanguage()
        .toLowerCase(Locale.ROOT);
  }
}
