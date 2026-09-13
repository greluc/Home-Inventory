/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.infrastructure;

import de.greluc.homeinv.inventory.api.ItemSearchQuery;
import de.greluc.homeinv.inventory.api.ItemView;
import de.greluc.homeinv.inventory.api.Valuation;
import de.greluc.homeinv.platform.CursorCodec;
import de.greluc.homeinv.platform.TenantContext;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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

  /** Removes the sensitive attributes this caller may not read (REQ-TEN-008). */
  private final de.greluc.homeinv.catalog.api.AttributeRedaction redaction;

  @Override
  public Page search(
      String text,
      String language,
      List<UUID> locationIds,
      Optional<CursorCodec.Position> after,
      int limit) {
    UUID tenantId = TenantContext.require();
    boolean filtered = text != null && !text.isBlank();
    boolean byLocation = locationIds != null && !locationIds.isEmpty();
    boolean resuming = after.isPresent();

    // Assembled from fixed fragments, never from input. The only variable parts
    // are the column and the configuration, and both come from vectorColumn and
    // regconfig, which map to literals.
    String sql =
        """
        select id, name, description, kind, location_id, quantity, quantity_unit,
               attributes::text as attributes, item_type_version_id, notes, minimum_stock,
               lifecycle_state, created_at, updated_at, version,
               purchase_amount, purchase_currency, purchased_on, purchase_source,
               warranty_until, lifetime_warranty,
               replacement_amount, replacement_currency, replacement_as_of, replacement_source,
               current_amount, current_currency, current_as_of
        from inventory.item
        where tenant_id = ?
          and deleted_at is null
        """
            + (filtered ? "  and %s @@ websearch_to_tsquery('%s', ?)%n".formatted(vectorColumn(language), regconfig(language)) : "")
            // The casts are required: PostgreSQL cannot infer parameter types
            // inside a row constructor comparison, and reports it as a grammar
            // error rather than as a type error.
            // `= any(?)` and not an IN list built from the ids: the number of
            // locations varies per request, and a generated IN list would be
            // both a new prepared statement each time and the one place in this
            // query where a value shapes the SQL.
            + (byLocation ? "  and location_id = any(?)%n".formatted() : "")
            + (resuming ? "  and (created_at, id) > (?::timestamptz, ?::uuid)%n".formatted() : "")
            + """
            order by created_at, id
            limit ?
            """;

    var spec = jdbc.sql(sql).param(tenantId);
    if (filtered) {
      spec = spec.param(text);
    }
    if (byLocation) {
      spec = spec.param(locationIds.toArray(UUID[]::new));
    }
    if (resuming) {
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

    List<ItemView> rows =
        spec.query(
                (rs, rowNum) ->
                    new ItemView(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getString("kind"),
                        rs.getObject("location_id", UUID.class),
                        rs.getBigDecimal("quantity"),
                        rs.getString("quantity_unit"),
                        // Redacted on the way out, per caller (REQ-TEN-008). A
                        // listing is the path that shows the most attributes at
                        // once, so it is the one that must not be forgotten.
                        redaction.forCaller(
                            rs.getObject("item_type_version_id", UUID.class),
                            rs.getObject("id", UUID.class),
                            rs.getString("attributes")),
                        rs.getString("notes"),
                        rs.getBigDecimal("minimum_stock"),
                        rs.getString("lifecycle_state"),
                        // OffsetDateTime, not Instant: the PostgreSQL driver
                        // refuses a direct conversion from timestamptz to
                        // Instant, and the error says so at runtime rather than
                        // at compile time.
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
                        valuationOf(rs),
                        rs.getLong("version")))
            .list();

    boolean hasMore = rows.size() > limit;
    List<ItemView> page = hasMore ? rows.subList(0, limit) : rows;
    Optional<CursorCodec.Position> last =
        hasMore
            ? Optional.of(
                new CursorCodec.Position(
                    page.get(page.size() - 1).createdAt(), page.get(page.size() - 1).id()))
            : Optional.empty();

    return new Page(List.copyOf(page), last);
  }
  /**
   * The three figures of REQ-LIFE-001/002/014, read off the row.
   *
   * <p>A listing shows them, so it reads them: an item with a purchase price whose list entry had
   * none would be a list nobody could total from.
   *
   * @param rs the row
   * @return the valuation
   * @throws SQLException when the row cannot be read
   */
  private static Valuation valuationOf(java.sql.ResultSet rs) throws SQLException {
    return new Valuation(
        money(rs.getBigDecimal("purchase_amount"), rs.getString("purchase_currency")),
        date(rs, "purchased_on"),
        rs.getString("purchase_source"),
        date(rs, "warranty_until"),
        rs.getBoolean("lifetime_warranty"),
        money(rs.getBigDecimal("replacement_amount"), rs.getString("replacement_currency")),
        date(rs, "replacement_as_of"),
        rs.getString("replacement_source") == null
            ? null
            : Valuation.Provenance.valueOf(rs.getString("replacement_source")),
        money(rs.getBigDecimal("current_amount"), rs.getString("current_currency")),
        date(rs, "current_as_of"));
  }

  /**
   * An amount and its code as one value.
   *
   * @param amount the amount column
   * @param currency the currency column
   * @return the money, or {@code null} when the row carries neither
   */
  private static de.greluc.homeinv.platform.Money money(
      java.math.BigDecimal amount, String currency) {
    return amount == null || currency == null
        ? null
        : new de.greluc.homeinv.platform.Money(amount, java.util.Currency.getInstance(currency));
  }

  /**
   * A {@code date} column as a {@link java.time.LocalDate}.
   *
   * @param rs the row
   * @param column which column
   * @return the date, or {@code null}
   * @throws SQLException when the row cannot be read
   */
  private static java.time.LocalDate date(java.sql.ResultSet rs, String column)
      throws SQLException {
    java.sql.Date value = rs.getDate(column);
    return value == null ? null : value.toLocalDate();
  }
}
