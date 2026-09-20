/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.infrastructure;

import de.greluc.homeinv.inventory.api.InsuranceReport;
import de.greluc.homeinv.inventory.api.ItemEvidence;
import de.greluc.homeinv.inventory.api.PlaceTree;
import de.greluc.homeinv.platform.Money;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The insurance report (REQ-LIFE-016).
 *
 * <p>One query for the lines, one for their evidence, and the grouping in Java. The alternative —
 * a query per room — is the shape that turns a household of two hundred things into two hundred
 * round trips for a document somebody prints once.
 */
@Component
@RequiredArgsConstructor
public class InsuranceReportAdapter implements InsuranceReport {

  /** The cap REQ-NFR-010 puts on every collection. */
  private static final int MAX_ITEMS = 200;

  /**
   * Everything with a replacement value, in a place, under a root.
   *
   * <p>A thing that has been sold or thrown away is left out: an insurer is told what a household
   * <b>has</b>. So is anything nobody has valued — counted separately rather than shown as a blank,
   * because a line with no figure in an insurance report is a line that will be argued about.
   */
  private static final String LINES =
      """
      select i.id as item_id, i.name, i.quantity,
             i.replacement_amount, i.replacement_currency, i.replacement_as_of,
             i.replacement_source, i.location_id
      from inventory.item i
      where i.deleted_at is null
        and i.lifecycle_state in ('ACTIVE', 'LENT')
        and i.replacement_amount is not null
        and (?::uuid is null or i.location_id = any(?::uuid[]))
      order by i.location_id, i.name
      limit ?
      """;

  private static final String WITHOUT_A_VALUE =
      """
      select count(*)
      from inventory.item i
      where i.deleted_at is null
        and i.lifecycle_state in ('ACTIVE', 'LENT')
        and i.replacement_amount is null
        and (?::uuid is null or i.location_id = any(?::uuid[]))
      """;

  private final JdbcClient jdbc;
  private final PlaceTree places;
  private final ItemEvidence evidence;
  private final Clock clock;

  @Override
  @Transactional(readOnly = true)
  public Report of(UUID root, int limit) {
    List<UUID> subtree = root == null ? List.of() : places.subtreeOf(root);
    String[] scope =
        root == null ? new String[0] : subtree.stream().map(UUID::toString).toArray(String[]::new);

    List<Line> flat =
        jdbc.sql(LINES)
            .param(root == null ? null : root.toString())
            .param(scope)
            .param(Math.clamp(limit, 1, MAX_ITEMS))
            .query(InsuranceReportAdapter::toLine)
            .list();

    long unvalued =
        jdbc.sql(WITHOUT_A_VALUE)
            .param(root == null ? null : root.toString())
            .param(scope)
            .query(Long.class)
            .single();

    Map<UUID, List<ItemEvidence.Attached>> attached =
        evidence.forItems(flat.stream().map(Line::itemId).toList());

    // Grouped here rather than by the query, because a room is a place and the
    // label of a place is `locations`' answer, not a column of `inventory.item`.
    Map<UUID, List<InsuranceReport.Line>> byRoom = new LinkedHashMap<>();
    for (Line line : flat) {
      byRoom
          .computeIfAbsent(line.locationId(), any -> new ArrayList<>())
          .add(lineOf(line, attached.getOrDefault(line.itemId(), List.of())));
    }

    List<Room> rooms = new ArrayList<>();
    List<Money> everything = new ArrayList<>();
    for (Map.Entry<UUID, List<InsuranceReport.Line>> room : byRoom.entrySet()) {
      List<Money> totals = totalsOf(room.getValue());
      rooms.add(
          new Room(
              room.getKey(),
              room.getKey() == null ? "" : places.labelOf(room.getKey()),
              room.getValue(),
              totals));
      everything = merge(everything, totals);
    }

    return new Report(LocalDate.now(clock), rooms, everything, false, unvalued);
  }

  /**
   * What a room's lines come to, per currency.
   *
   * <p>Per currency and never mixed (REQ-LIFE-017). Two currencies are two lines and a statement
   * that nothing was converted, never one number and never an approximation.
   *
   * @param lines the room's lines
   * @return the totals
   */
  private static List<Money> totalsOf(List<InsuranceReport.Line> lines) {
    Map<Currency, BigDecimal> byCurrency = new LinkedHashMap<>();
    for (InsuranceReport.Line line : lines) {
      Money replacement = line.replacement();
      if (replacement == null) {
        continue;
      }
      // Times the quantity: two of a thing cost twice as much to replace, and a
      // report that ignored the count would understate a shelf of the same tool.
      BigDecimal amount =
          replacement.amount().multiply(line.quantity() == null ? BigDecimal.ONE : line.quantity());
      byCurrency.merge(replacement.currency(), amount, BigDecimal::add);
    }
    return byCurrency.entrySet().stream()
        .map(entry -> new Money(entry.getValue(), entry.getKey()))
        .toList();
  }

  /**
   * Two lists of totals added together, per currency.
   *
   * @param left one
   * @param right the other
   * @return the sums
   */
  private static List<Money> merge(List<Money> left, List<Money> right) {
    Map<Currency, BigDecimal> byCurrency = new LinkedHashMap<>();
    for (Money money : left) {
      byCurrency.merge(money.currency(), money.amount(), BigDecimal::add);
    }
    for (Money money : right) {
      byCurrency.merge(money.currency(), money.amount(), BigDecimal::add);
    }
    return byCurrency.entrySet().stream()
        .map(entry -> new Money(entry.getValue(), entry.getKey()))
        .toList();
  }

  /**
   * One line, with its evidence attached.
   *
   * @param row what the query said
   * @param attached what backs it up
   * @return the line
   */
  private static InsuranceReport.Line lineOf(Line row, List<ItemEvidence.Attached> attached) {
    Evidence photo =
        attached.stream()
            .filter(ItemEvidence.Attached::primaryImage)
            .findFirst()
            .map(InsuranceReportAdapter::evidenceOf)
            .orElse(null);
    List<Evidence> receipts =
        attached.stream()
            .filter(one -> "RECEIPT".equals(one.role()) || "WARRANTY_PROOF".equals(one.role()))
            .map(InsuranceReportAdapter::evidenceOf)
            .toList();
    return new InsuranceReport.Line(
        row.itemId(),
        row.name(),
        row.quantity(),
        row.replacement(),
        row.asOf(),
        row.source(),
        photo,
        receipts);
  }

  /**
   * One attachment as the report shows it.
   *
   * @param attached the attachment
   * @return the evidence
   */
  private static Evidence evidenceOf(ItemEvidence.Attached attached) {
    return new Evidence(
        attached.mediaObjectId(), attached.mediaType(), attached.byteSize(), attached.role());
  }

  /**
   * One row of the query.
   *
   * @param rs the row
   * @param rowNum which row
   * @return it
   * @throws SQLException when it cannot be read
   */
  private static Line toLine(ResultSet rs, int rowNum) throws SQLException {
    String currency = rs.getString("replacement_currency");
    return new Line(
        rs.getObject("item_id", UUID.class),
        rs.getString("name"),
        rs.getBigDecimal("quantity"),
        currency == null
            ? null
            : new Money(rs.getBigDecimal("replacement_amount"), Currency.getInstance(currency)),
        rs.getObject("replacement_as_of", LocalDate.class),
        rs.getString("replacement_source"),
        rs.getObject("location_id", UUID.class));
  }

  /**
   * One item, before it is grouped into a room.
   *
   * @param itemId the item
   * @param name what it is called
   * @param quantity how many
   * @param replacement what replacing it would cost
   * @param asOf the day that figure was true
   * @param source who said so
   * @param locationId where it is
   */
  private record Line(
      UUID itemId,
      String name,
      BigDecimal quantity,
      Money replacement,
      LocalDate asOf,
      String source,
      UUID locationId) {}
}
