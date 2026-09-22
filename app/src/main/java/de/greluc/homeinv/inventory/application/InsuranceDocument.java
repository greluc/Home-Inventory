/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.application;

import de.greluc.homeinv.inventory.api.InsuranceReport;
import de.greluc.homeinv.platform.Money;
import de.greluc.homeinv.plugin.api.port.DocumentRenderer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The insurance report, described as a document (REQ-LIFE-016, ADR-0070).
 *
 * <h2>What this is and is not</h2>
 *
 * <p>It decides what the document <b>says</b>: which figures appear, in what order, under which
 * headings, with what already formatted. It decides nothing about what the document <b>looks
 * like</b> — no fonts, no spacing, no page breaking beyond "start a new page per room", which is a
 * statement about the report rather than about typography. That division is ADR-0070's and it is
 * what lets one renderer serve every document this system will ever describe.
 *
 * <p>Money is formatted here for the same reason. The core knows the currency, the scale and the
 * rule that a total is never mixed across currencies (REQ-LIFE-017); a renderer knows none of the
 * three, and a renderer formatting money would be a second place for that rule to be wrong.
 */
public final class InsuranceDocument {

  private InsuranceDocument() {}

  /**
   * Describes a report.
   *
   * @param report what the figures are
   * @param photos the primary photograph of each item that has one, by item id. Bytes rather than
   *     references: a renderer has no route to the blob store and the core hands out no URL
   *     (REQ-SEC-034)
   * @param tenantName whose inventory it is, for the title
   * @return the document
   */
  public static DocumentRenderer.Document of(
      InsuranceReport.Report report, Map<java.util.UUID, de.greluc.homeinv.inventory.api.ItemEvidence.Picture> photos, String tenantName) {
    List<DocumentRenderer.Block> blocks = new ArrayList<>();

    blocks.add(new DocumentRenderer.Heading(1, "Insurance report"));
    blocks.add(
        new DocumentRenderer.Facts(
            null,
            List.of(
                new DocumentRenderer.Fact("Inventory", tenantName == null ? "" : tenantName),
                new DocumentRenderer.Fact("Produced on", String.valueOf(report.producedOn())),
                new DocumentRenderer.Fact("Figure", "Replacement value"),
                new DocumentRenderer.Fact(
                    "Currency conversion", report.converted() ? "applied" : "none"))));

    // The total first, because it is the number somebody is looking for, and the
    // statement about what is missing directly under it rather than at the end
    // where it would be read after the figure had already been believed.
    blocks.add(new DocumentRenderer.Heading(2, "Total"));
    blocks.add(new DocumentRenderer.Facts("Replacement value", factsOf(report.totals())));
    if (report.withoutAReplacementValue() > 0) {
      blocks.add(
          new DocumentRenderer.Paragraph(
              report.withoutAReplacementValue()
                  + " item(s) are not in this report, because nobody has recorded what replacing "
                  + "them would cost. They are not worth nothing; they are unvalued."));
    }

    for (InsuranceReport.Room room : report.rooms()) {
      blocks.add(new DocumentRenderer.PageBreak());
      blocks.add(new DocumentRenderer.Heading(2, room.path()));
      blocks.add(new DocumentRenderer.Facts("Replacement value", factsOf(room.totals())));

      for (InsuranceReport.Line line : room.lines()) {
        blocks.add(new DocumentRenderer.Heading(3, line.name()));
        de.greluc.homeinv.inventory.api.ItemEvidence.Picture photo =
            photos.get(line.itemId());
        if (photo != null) {
          blocks.add(
              new DocumentRenderer.Image(photo.content(), photo.mediaType(), line.name(), 60));
        }
        blocks.add(
            new DocumentRenderer.Facts(
                null,
                List.of(
                    new DocumentRenderer.Fact("Quantity", plain(line.quantity())),
                    new DocumentRenderer.Fact("Replacement value", money(line.replacement())),
                    new DocumentRenderer.Fact(
                        "As of", line.asOf() == null ? "—" : String.valueOf(line.asOf())),
                    new DocumentRenderer.Fact(
                        "Figure from", line.source() == null ? "—" : readable(line.source())),
                    new DocumentRenderer.Fact("Evidence", evidenceOf(line)))));
      }
    }

    Map<String, String> metadata = new LinkedHashMap<>();
    metadata.put("subject", "Replacement value of the contents");
    metadata.put("generatedAt", String.valueOf(report.producedOn()));
    return new DocumentRenderer.Document(
        "Insurance report — " + report.producedOn(),
        metadata,
        new DocumentRenderer.Page("A4", false, 18),
        List.copyOf(blocks));
  }

  /**
   * A line's evidence, said in words.
   *
   * @param line the line
   * @return what backs it up
   */
  private static String evidenceOf(InsuranceReport.Line line) {
    List<String> had = new ArrayList<>();
    if (line.photo() != null) {
      had.add("photograph");
    }
    long receipts =
        line.receipts().stream().filter(one -> "RECEIPT".equals(one.role())).count();
    long proofs =
        line.receipts().stream().filter(one -> "WARRANTY_PROOF".equals(one.role())).count();
    if (receipts > 0) {
      had.add(receipts + " receipt(s)");
    }
    if (proofs > 0) {
      had.add(proofs + " warranty proof(s)");
    }
    return had.isEmpty() ? "none on file" : String.join(", ", had);
  }

  /**
   * A list of money as labelled facts, one per currency.
   *
   * <p>One line per currency and never a sum across them (REQ-LIFE-017). A document that added two
   * currencies together would be the one place in this system where that rule did not hold.
   *
   * @param totals the amounts
   * @return the facts
   */
  private static List<DocumentRenderer.Fact> factsOf(List<Money> totals) {
    if (totals.isEmpty()) {
      return List.of(new DocumentRenderer.Fact("Total", "—"));
    }
    return totals.stream()
        .map(
            total ->
                new DocumentRenderer.Fact(
                    total.currency().getCurrencyCode(), total.amount().toPlainString()))
        .toList();
  }

  /**
   * One amount, with its currency beside it.
   *
   * @param money the amount, or null
   * @return it as text
   */
  private static String money(Money money) {
    return money == null
        ? "—"
        : money.amount().toPlainString() + " " + money.currency().getCurrencyCode();
  }

  /**
   * A number without exponent notation, which a document should never show.
   *
   * @param value the number, or null
   * @return it as text
   */
  private static String plain(java.math.BigDecimal value) {
    return value == null ? "1" : value.stripTrailingZeros().toPlainString();
  }

  /**
   * A provenance as a reader would say it.
   *
   * @param source {@code MANUAL} or {@code PLUGIN}
   * @return words
   */
  private static String readable(String source) {
    return switch (source) {
      case "MANUAL" -> "entered by hand";
      case "PLUGIN" -> "estimated by a valuation plugin";
      case "DEPRECIATION" -> "depreciated from the purchase price";
      default -> source;
    };
  }
}
