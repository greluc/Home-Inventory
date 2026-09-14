/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.infrastructure;

import de.greluc.homeinv.catalog.api.FieldDataType;
import de.greluc.homeinv.catalog.api.TypeRegistry;
import de.greluc.homeinv.platform.SortOrder;
import de.greluc.homeinv.inventory.api.ItemSearchQuery;
import de.greluc.homeinv.platform.CursorCodec;
import de.greluc.homeinv.platform.QueryFilter;
import de.greluc.homeinv.platform.TenantContext;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Full-text search over the item table, with keyset pagination.
 *
 * <h2>Why the language picks the column rather than a parameter</h2>
 *
 * <p>{@code search_vector_de} and {@code search_vector_en} are {@code GENERATED ALWAYS} columns, one
 * per shipped UI language (ADR-0047). A per-tenant text search configuration is not possible:
 * {@code to_tsvector} is immutable only with a literal configuration, and a non-immutable expression
 * cannot be a generated column. So the language selects which column to match against, and the
 * choice is made from a closed set — never interpolated, because a column name from user input is a
 * SQL injection with extra steps.
 *
 * <h2>Why keyset and not offset</h2>
 *
 * <p>{@code REQ-SRCH-009} forbids offsets, and the reason is not only performance. An offset is a
 * position in a result set that is still changing: insert a row while somebody pages, and page two
 * repeats a row page one already showed, or skips one entirely. Keyset pagination resumes at a value
 * rather than at a count, so a concurrent insert can only ever add rows a reader has not reached.
 */
@Component
@RequiredArgsConstructor
public class ItemSearchAdapter implements ItemSearchQuery {

  /**
   * The columns a language may select. A closed map, not a format string: the value decides a column
   * name, and the only safe way to let input decide a column name is to let it decide only between
   * values that were written here.
   */
  private static boolean isEnglish(String language) {
    // Exact, and deliberately not case-insensitive. Case folding is
    // locale-dependent — in Turkish `I` folds to a dotless character and "EN"
    // stops equalling "en" — and this value decides which generated column a
    // query reads. The API accepts the two-letter code the principal's display
    // language produces, which is lowercase; a caller that sends "EN" gets the
    // German vector, which is a wrong answer rather than a silent one.
    return "en".equals(language);
  }

  private static String vectorColumn(String language) {
    return isEnglish(language) ? "search_vector_en" : "search_vector_de";
  }

  private static String regconfig(String language) {
    return isEnglish(language) ? "english" : "german";
  }

  private final JdbcClient jdbc;

  /** Where a sortable field's storage class comes from — the allowlist, never the caller. */
  private final TypeRegistry types;

  /**
   * The {@code where} clause every question here shares, and the values it binds.
   *
   * <p>Written once because four methods ask the same question of the same rows and differ only in
   * what they select: a page of ids, a count per column, a count per attribute value, or every id.
   * A second copy of this clause is how a facet comes to count rows the list does not show.
   *
   * @param sql the fragment, beginning with {@code where}
   * @param values what to bind, in the order the fragment names them
   */
  private record Where(String sql, List<Object> values) {}

  /**
   * Builds the shared {@code where} clause.
   *
   * <p>Assembled from fixed fragments, never from input. The variable parts are the vector column
   * and the text search configuration, both from closed sets, and the attribute predicates, whose
   * field keys the application layer has already checked against the tenant's allowlist
   * (REQ-SEC-031).
   *
   * @param criteria what narrows the query
   * @return the clause and its values
   */
  private Where whereFor(Criteria criteria) {
    UUID tenantId = TenantContext.require();
    String text = criteria.text();
    List<UUID> locationIds = criteria.locationIds();
    List<UUID> typeVersionIds = criteria.typeVersionIds();
    List<UUID> itemIds = criteria.itemIds();
    List<QueryFilter> filters = criteria.filters() == null ? List.of() : criteria.filters();

    boolean filtered = text != null && !text.isBlank();
    List<UUID> alsoMatched =
        criteria.textMatchedItemIds() == null ? List.of() : criteria.textMatchedItemIds();
    List<UUID> alsoHere =
        criteria.textMatchedLocationIds() == null ? List.of() : criteria.textMatchedLocationIds();
    boolean byLocation = locationIds != null && !locationIds.isEmpty();
    boolean byType = typeVersionIds != null && !typeVersionIds.isEmpty();
    boolean byId = itemIds != null && !itemIds.isEmpty();

    String sql =
        """
        where i.tenant_id = ?
          and i.deleted_at is null
        """
            // Four matches joined by "or", which is REQ-SRCH-011's list. The
            // first two are this block's own: the generated vector over name,
            // description and notes, and the mirrored attribute values. The other
            // two arrived as ids from the blocks that own them, because a
            // generated column can only read its own row.
            + (filtered
                ? """
                    and (
                      i.%s @@ websearch_to_tsquery('%s', ?)
                      or exists (select 1 from inventory.item_attr_index v
                                  where v.tenant_id = i.tenant_id and v.item_id = i.id
                                    and to_tsvector('%s', coalesce(v.text_value, ''))
                                        @@ websearch_to_tsquery('%s', ?))%s%s
                    )
                    """
                    .formatted(
                        vectorColumn(criteria.language()),
                        regconfig(criteria.language()),
                        regconfig(criteria.language()),
                        regconfig(criteria.language()),
                        alsoMatched.isEmpty() ? "" : "%n                      or i.id = any(?)".formatted(),
                        alsoHere.isEmpty()
                            ? ""
                            : "%n                      or i.location_id = any(?)".formatted())
                : "")
            // `= any(?)` and not an IN list built from the ids: the number of
            // locations varies per request, and a generated IN list would be
            // both a new prepared statement each time and the one place in this
            // query where a value shapes the SQL.
            + (byLocation ? "  and i.location_id = any(?)%n".formatted() : "")
            // The two restrictions another block resolved. Both are `= any(?)`
            // for the same reason as the locations above: the count varies per
            // request, and a generated IN list would be the one place a value
            // shapes the statement.
            + (byType ? "  and i.item_type_version_id = any(?)%n".formatted() : "")
            + (byId ? "  and i.id = any(?)%n".formatted() : "")
            // One `exists` per filter rather than one join per filter. A join
            // would multiply the rows when two filters match two different
            // attributes of the same item, and the fix for that is a `distinct`
            // that throws away the sort order with the duplicates.
            + filters.stream().map(this::predicateFor).collect(Collectors.joining());

    List<Object> values = new java.util.ArrayList<>();
    values.add(tenantId);
    if (filtered) {
      // Twice: once for the row's vector and once for the attribute values.
      values.add(text);
      values.add(text);
      if (!alsoMatched.isEmpty()) {
        values.add(alsoMatched.toArray(UUID[]::new));
      }
      if (!alsoHere.isEmpty()) {
        values.add(alsoHere.toArray(UUID[]::new));
      }
    }
    if (byLocation) {
      values.add(locationIds.toArray(UUID[]::new));
    }
    if (byType) {
      values.add(typeVersionIds.toArray(UUID[]::new));
    }
    if (byId) {
      values.add(itemIds.toArray(UUID[]::new));
    }
    for (QueryFilter filter : filters) {
      values.add(filter.field());
      if (filter.unit() != null) {
        values.add(filter.unit());
      }
      values.addAll(filter.values());
    }
    return new Where(sql, values);
  }

  @Override
  public Map<UUID, Long> countByColumn(Criteria criteria, CountColumn column) {
    // From a closed set of two, which is what makes this a checked builder and
    // not a column name from a request (REQ-SEC-031).
    String grouped =
        switch (column) {
          case TYPE_VERSION -> "i.item_type_version_id";
          case LOCATION -> "i.location_id";
        };
    Where where = whereFor(criteria);
    String sql =
        """
        select %s as bucket, count(*) as total
        from inventory.item i
        %s  and %s is not null
        group by 1
        """
            .formatted(grouped, where.sql(), grouped);

    var spec = jdbc.sql(sql);
    for (Object value : where.values()) {
      spec = spec.param(value);
    }
    Map<UUID, Long> counts = new java.util.LinkedHashMap<>();
    spec.query(
            (rs, rowNum) ->
                Map.entry(rs.getObject("bucket", UUID.class), rs.getLong("total")))
        .list()
        .forEach(entry -> counts.put(entry.getKey(), entry.getValue()));
    return counts;
  }

  @Override
  public Map<String, Long> countByAttribute(Criteria criteria, String fieldKey) {
    Where where = whereFor(criteria);
    // `coalesce` across the storage classes rather than a column chosen from the
    // field's type: a facet's bucket is the token a filter takes back, and that
    // is text whichever column the value lives in. Exactly one of them is
    // non-null per row, because the projector writes one (AttributeProjector).
    String sql =
        """
        select coalesce(
                 f.text_value,
                 f.num_value::text,
                 to_char(f.date_value at time zone 'UTC', 'YYYY-MM-DD'),
                 f.bool_value::text,
                 f.ref_value::text) as bucket,
               count(*) as total
        from inventory.item i
        join inventory.item_attr_index f
          on f.tenant_id = i.tenant_id and f.item_id = i.id and f.field_key = ?
        %s
        group by 1
        having coalesce(
                 f.text_value,
                 f.num_value::text,
                 to_char(f.date_value at time zone 'UTC', 'YYYY-MM-DD'),
                 f.bool_value::text,
                 f.ref_value::text) is not null
        """
            .formatted(where.sql());

    // The join's parameter comes first, because the join is written before the
    // where clause.
    var spec = jdbc.sql(sql).param(fieldKey);
    for (Object value : where.values()) {
      spec = spec.param(value);
    }
    Map<String, Long> counts = new java.util.LinkedHashMap<>();
    spec.query((rs, rowNum) -> Map.entry(rs.getString("bucket"), rs.getLong("total")))
        .list()
        .forEach(entry -> counts.put(entry.getKey(), entry.getValue()));
    return counts;
  }

  /**
   * Its own read transaction, unlike every other method here.
   *
   * <p>The rest are called from {@code DefaultSearchService.query}, which already has one. This one
   * is called by the indexer, from a broker listener that has none — and without a transaction
   * there is no {@code SET LOCAL app.tenant_id}, so row-level security answers nothing and every
   * item reads as gone. The indexer would then remove the document of an item that was just
   * created, silently, because removing what is not there is not an error. It did, until this
   * annotation was added on 2026-09-14.
   *
   * @param itemId the item
   * @return what to index, or empty when the tenant has no such live item
   */
  @org.springframework.transaction.annotation.Transactional(readOnly = true)
  @Override
  public Optional<SearchableItem> searchable(UUID itemId) {
    UUID tenantId = TenantContext.require();
    // Two statements rather than one join: the attribute values multiply the row
    // and a `string_agg` would hand back a delimiter nobody chose. The second is
    // a lookup on `iai_item`, over the rows the first has just touched.
    List<SearchableItem> rows =
        jdbc.sql(
                """
                select i.id, i.item_type_version_id, i.name, i.description, i.notes,
                       i.location_id, i.created_at, i.updated_at
                from inventory.item i
                where i.tenant_id = ? and i.id = ? and i.deleted_at is null
                """)
            .params(tenantId, itemId)
            .query(
                (rs, rowNum) ->
                    new SearchableItem(
                        rs.getObject("id", UUID.class),
                        rs.getObject("item_type_version_id", UUID.class),
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getString("notes"),
                        rs.getObject("location_id", UUID.class),
                        List.of(),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("updated_at", OffsetDateTime.class).toInstant()))
            .list();
    if (rows.isEmpty()) {
      return Optional.empty();
    }

    // From the side table and not from the JSONB: the projector has already
    // dropped what may not be indexed - a `sensitive` field is ciphertext and is
    // never mirrored (ADR-0019) - and rendered every value as something
    // comparable. Reading the JSONB here would have to repeat both rules.
    List<String> values =
        jdbc.sql(
                """
                select coalesce(
                         f.text_value,
                         f.num_value::text,
                         to_char(f.date_value at time zone 'UTC', 'YYYY-MM-DD'),
                         f.bool_value::text,
                         f.ref_value::text) as value
                from inventory.item_attr_index f
                where f.tenant_id = ? and f.item_id = ?
                """)
            .params(tenantId, itemId)
            .query(String.class)
            .list()
            .stream()
            .filter(value -> value != null && !value.isBlank())
            .toList();

    SearchableItem row = rows.getFirst();
    return Optional.of(
        new SearchableItem(
            row.itemId(),
            row.itemTypeVersionId(),
            row.name(),
            row.description(),
            row.notes(),
            row.locationId(),
            values,
            row.createdAt(),
            row.updatedAt()));
  }

  @Override
  public List<UUID> matchingIds(Criteria criteria) {
    Where where = whereFor(criteria);
    String sql =
        """
        select i.id
        from inventory.item i
        %s
        """
            .formatted(where.sql());
    var spec = jdbc.sql(sql);
    for (Object value : where.values()) {
      spec = spec.param(value);
    }
    return spec.query((rs, rowNum) -> rs.getObject("id", UUID.class)).list();
  }

  @Override
  public Rows search(
      Criteria criteria, Optional<CursorCodec.Position> after, SortOrder sort, int limit) {
    boolean resuming = after.isPresent();
    SortPlan plan = planFor(sort);
    Where where = whereFor(criteria);

    // Assembled from fixed fragments, never from input. The one variable part
    // beyond the shared clause is the sort expression, which `planFor` resolves
    // from a field the application layer has already checked against the
    // tenant's allowlist.
    String sql =
        """
        select i.id, i.created_at%s
        from inventory.item i
        %s
        %s"""
                .formatted(plan.selectSuffix(), plan.join(), where.sql())
            + (resuming ? "  and " + plan.resume(after.get().sortValue()) + "%n".formatted() : "")
            + "order by " + plan.orderBy() + "%n".formatted()
            + "limit ?%n".formatted();

    var spec = jdbc.sql(sql);
    if (plan.attributeKey() != null) {
      // The join's parameter comes first, because the join is written before the
      // where clause.
      spec = spec.param(plan.attributeKey());
    }
    for (Object value : where.values()) {
      spec = spec.param(value);
    }
    if (resuming) {
      for (String value : plan.split(after.get().sortValue())) {
        spec = spec.param(value);
      }
      // OffsetDateTime, for the same reason the reader uses it: the driver has no
      // direct mapping for Instant on a timestamptz parameter either.
      spec =
          spec.param(after.get().createdAt().atOffset(java.time.ZoneOffset.UTC))
              .param(after.get().id());
    }
    // One more than asked for, so the caller learns whether another page exists
    // without a second count query - which would be a second answer that can
    // disagree with the first.
    spec = spec.param(limit + 1);

    // Identifiers and the sort key, nothing else. The rows are loaded by
    // `ItemService.byIds`, which is the path every other read takes and
    // therefore the one place the redaction lives (REQ-SRCH-007).
    record Hit(UUID id, java.time.Instant createdAt, String sortValue) {}
    List<Hit> hits =
        spec.query(
                (rs, rowNum) ->
                    new Hit(
                        rs.getObject("id", UUID.class),
                        // OffsetDateTime, not Instant: the PostgreSQL driver
                        // refuses a direct conversion from timestamptz to
                        // Instant, and the error says so at runtime rather than
                        // at compile time.
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        plan.readSortValue(rs)))
            .list();

    boolean hasMore = hits.size() > limit;
    List<Hit> page = hasMore ? hits.subList(0, limit) : hits;
    Optional<CursorCodec.Position> last =
        hasMore
            ? Optional.of(
                new CursorCodec.Position(
                    page.get(page.size() - 1).createdAt(),
                    page.get(page.size() - 1).id(),
                    page.get(page.size() - 1).sortValue()))
            : Optional.empty();

    return new Rows(page.stream().map(Hit::id).toList(), last);
  }

  /**
   * How one ordering becomes SQL.
   *
   * <p>Everything about a sort that the statement needs, worked out once: which expressions to order
   * by, whether the side table has to be joined, and how a cursor resumes in the middle of it.
   *
   * <p>Money and quantity carry <b>two</b> expressions, {@code unit_value} before {@code num_value}
   * (decided with the owner, 2026-09-14). Without the unit an ordering would put 90 USD before
   * 100 EUR and claim a ranking that does not exist without an exchange rate — which ADR-0025
   * forbids the core to have. `iai_unit (tenant_id, field_key, unit_value, num_value)` is the index
   * that makes it free.
   *
   * @param expressions the SQL expressions to order by, in order
   * @param descending whether to reverse them
   * @param attributeKey the attribute to join on, or {@code null} for a column of the item itself
   */
  private record SortPlan(List<String> expressions, boolean descending, String attributeKey) {

    /** What separates two sort values inside one opaque cursor string. */
    private static final String SEPARATOR = "\u001f";

    /** The default: no expression of its own, just the tie-break every order ends with. */
    static SortPlan none() {
      return new SortPlan(List.of(), false, null);
    }

    boolean isDefault() {
      return expressions.isEmpty();
    }

    String selectSuffix() {
      if (isDefault()) {
        return "";
      }
      StringBuilder select = new StringBuilder();
      for (int index = 0; index < expressions.size(); index++) {
        select.append(", ").append(expressions.get(index)).append(" as sort_").append(index);
      }
      return select.toString();
    }

    String join() {
      return attributeKey == null
          ? ""
          // LEFT, because an item that does not have the field still belongs in
          // the list -- it sorts to the end rather than disappearing.
          : """
            left join inventory.item_attr_index a
              on a.tenant_id = i.tenant_id and a.item_id = i.id and a.field_key = ?
            """;
    }

    String orderBy() {
      if (isDefault()) {
        return "i.created_at, i.id";
      }
      String direction = descending ? " desc" : " asc";
      StringBuilder order = new StringBuilder();
      for (String expression : expressions) {
        // NULLS LAST in both directions. PostgreSQL would put them first when
        // descending, and a person who clicks a column heading twice should not
        // be handed a page of items that do not have the field at all.
        order.append(expression).append(direction).append(" nulls last, ");
      }
      return order + "i.created_at, i.id";
    }

    /**
     * The predicate that resumes after the last row of the previous page.
     *
     * <p>Three cases, and the middle one is the one that is easy to get wrong. Within the rows that
     * have a value, the next page starts after {@code (value, created_at, id)}. Once the cursor
     * names no value, every remaining row is one without the field, so only the tie-break advances.
     * And a row that has no value always comes after one that does, which is what carries a reader
     * from the first region into the second.
     *
     * @param sortValue the cursor's sort value, or {@code null} when it had none
     * @return the SQL, with parameters in the order {@link #split} produces them
     */
    String resume(String sortValue) {
      if (isDefault()) {
        return "(i.created_at, i.id) > (?::timestamptz, ?::uuid)";
      }
      String columns = String.join(", ", expressions);
      String placeholders = expressions.stream().map(e -> "?").collect(java.util.stream.Collectors.joining(", "));
      String firstNull = expressions.get(0) + " is null";
      if (sortValue == null) {
        return "(" + firstNull + " and (i.created_at, i.id) > (?::timestamptz, ?::uuid))";
      }
      String comparison = descending ? "<" : ">";
      return "(("
          + columns
          + ") "
          + comparison
          + " ("
          + placeholders
          + ") or (("
          + columns
          + ") = ("
          + placeholders
          + ") and (i.created_at, i.id) > (?::timestamptz, ?::uuid)) or "
          + firstNull
          + ")";
    }


    /**
     * The cursor's sort value as the parameters the predicate expects.
     *
     * @param sortValue the opaque value, or {@code null}
     * @return one parameter per expression, twice over when the predicate compares twice
     */
    List<String> split(String sortValue) {
      if (isDefault() || sortValue == null) {
        return List.of();
      }
      List<String> parts = List.of(sortValue.split(SEPARATOR, -1));
      // Once for the inequality and once for the equality branch.
      List<String> both = new java.util.ArrayList<>(parts);
      both.addAll(parts);
      return both;
    }

    /**
     * The sort value of one row, as the cursor will carry it.
     *
     * @param rs the row
     * @return the value, or {@code null} when the row has none
     * @throws java.sql.SQLException when the row cannot be read
     */
    String readSortValue(java.sql.ResultSet rs) throws java.sql.SQLException {
      if (isDefault()) {
        return null;
      }
      StringBuilder value = new StringBuilder();
      for (int index = 0; index < expressions.size(); index++) {
        String part = rs.getString("sort_" + index);
        if (part == null) {
          return null;
        }
        if (index > 0) {
          value.append(SEPARATOR);
        }
        value.append(part);
      }
      return value.toString();
    }
  }

  /**
   * One filter, as an {@code exists} over the side table.
   *
   * <p>Exactly one column, resolved from the field's declared type, because
   * {@code item_attr_index} keeps one column per storage class and a value lives in one of them.
   * Comparing in all of them and hoping one matches would bind the value once per column and match
   * a row whose value is in another. The comparison carries a cast so PostgreSQL reads the
   * parameter as the column's type rather than as text — {@code '90' > '100'} is true of
   * strings and false of numbers.
   *
   * <p>A unit, where one is given, is part of the predicate and not an afterthought: without it the
   * comparison would range over every currency at once, which is the plausible wrong answer
   * {@code unit_value} exists to prevent.
   *
   * @param filter one condition, already checked against the allowlist
   * @return the SQL fragment, with its parameters in the order they are bound
   */
  private String predicateFor(QueryFilter filter) {
    FieldDataType type =
        types.queryableFields().stream()
            .filter(field -> field.key().equals(filter.field()) && field.filterable())
            .findFirst()
            .map(TypeRegistry.QueryableField::dataType)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Cannot filter by " + filter.field() + "; it is not filterable"));

    String column =
        switch (type.storageClass()) {
          case NUMBER -> "f.num_value";
          case TEXT -> "f.text_value";
          case DATE -> "f.date_value";
          case BOOLEAN -> "f.bool_value";
          case REFERENCE -> "f.ref_value";
          case NONE ->
              throw new IllegalArgumentException(
                  "Cannot filter by " + filter.field() + "; it is not mirrored for filtering");
        };
    String cast =
        switch (type.storageClass()) {
          case NUMBER -> "?::numeric";
          case DATE -> "?::timestamptz";
          case BOOLEAN -> "?::boolean";
          case REFERENCE -> "?::uuid";
          default -> "?";
        };

    String unit = filter.unit() == null ? "" : " and f.unit_value = ?";
    String comparison =
        switch (filter.operator()) {
          case EQ -> column + " = " + cast;
          case IN ->
              column
                  + " in ("
                  + String.join(", ", java.util.Collections.nCopies(filter.values().size(), cast))
                  + ")";
          case GT -> column + " > " + cast;
          case GTE -> column + " >= " + cast;
          case LT -> column + " < " + cast;
          case LTE -> column + " <= " + cast;
          // Never reached: `subtree` walks the location tree, `search` resolves
          // it to ids through `locations`, and only attribute conditions get
          // this far. Named rather than defaulted, so that adding an operator
          // fails here instead of silently choosing one.
          case SUBTREE ->
              throw new IllegalArgumentException("An attribute has no subtree to walk");
        };
    // One template with two holes, never a query joined to an expression: the
    // holes hold a column name from the switch above and an operator, and
    // REQ-SEC-031 is that the difference is visible in the source rather than
    // argued for in a comment.
    return """
             and exists (select 1 from inventory.item_attr_index f
                          where f.tenant_id = i.tenant_id
                            and f.item_id = i.id
                            and f.field_key = ?%s
                            and %s)%n"""
        .formatted(unit, comparison);
  }

  /**
   * Resolves an ordering to the columns that express it.
   *
   * <p>The field name has already been checked against the tenant's allowlist by the application
   * layer, so what arrives here is one of a closed set. It is mapped rather than interpolated all
   * the same: a column name assembled from a string is a SQL injection with extra steps, however
   * carefully the string was vetted one layer up.
   *
   * @param sort what was asked for, or {@code null}
   * @return the plan
   */
  private SortPlan planFor(SortOrder sort) {
    if (sort == null) {
      return SortPlan.none();
    }
    if (!sort.isAttribute()) {
      String column =
          switch (sort.field()) {
            case "name" -> "i.name";
            case "createdAt" -> "i.created_at";
            case "updatedAt" -> "i.updated_at";
            default ->
                throw new IllegalArgumentException(
                    "Cannot sort by " + sort.field() + "; it is not a column of an item");
          };
      return new SortPlan(List.of(column), sort.descending(), null);
    }

    String key = sort.attributeKey();
    FieldDataType type =
        types.queryableFields().stream()
            .filter(field -> field.key().equals(key) && field.sortable())
            .findFirst()
            .map(TypeRegistry.QueryableField::dataType)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Cannot sort by " + sort.field() + "; no published type marks it sortable"));

    List<String> expressions =
        type.carriesUnit()
            // The unit first, always. `iai_unit` is ordered the same way, so this
            // is the index's own order rather than a sort on top of it.
            ? List.of("a.unit_value", "a.num_value")
            : List.of(
                switch (type.storageClass()) {
                  case NUMBER -> "a.num_value";
                  case TEXT -> "a.text_value";
                  case DATE -> "a.date_value";
                  case BOOLEAN -> "a.bool_value";
                  case REFERENCE -> "a.ref_value";
                  case NONE ->
                      throw new IllegalArgumentException(
                          "Cannot sort by " + sort.field() + "; it is not mirrored for ordering");
                });
    return new SortPlan(expressions, sort.descending(), key);
  }
}
