/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.portability.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What a mapped CSV row turns into, and who turns it into one (REQ-PORT-001, REQ-PORT-002).
 *
 * <h2>Three ports, and why not one call into three blocks</h2>
 *
 * <p>{@code portability} reads the file and decides what each column means. It must not then reach
 * into {@code inventory}, {@code locations} and {@code tagging} to write the result: those three
 * already depend on {@code portability.api} to export and import themselves, so a call the other
 * way would close a cycle and the modularity check would fail the build (ADR-0002).
 *
 * <p>So the dependency is inverted here as it is everywhere else in this block. Each of the three
 * implements the small port it owns, {@code portability} calls the ports, and nothing points at a
 * block that points back.
 */
public interface CsvIngest {

  /**
   * Creates or updates one item from a mapped row.
   *
   * <p>Called inside the import's single transaction (REQ-PORT-007), with the tenant context set.
   * An implementation validates the attributes against the type version exactly as the API does —
   * an import is not a way past the rules a type declares.
   *
   * @param item what the row said
   * @param existing the item this row updated last time, or null to create a new one
   * @param actor who is importing, recorded as the author of what this writes
   * @return the item, and the attribute keys it could not write
   */
  Ingested ingest(MappedItem item, UUID existing, UUID actor);

  /**
   * One item, and what did not fit.
   *
   * @param itemId the item that was created or updated
   * @param fieldsNotDeclared attribute keys the item type does not declare, which were skipped.
   *     Reported once per import rather than refused per row: a file from another system always
   *     carries columns this one has no field for, and the person importing wants to know which
   *     rather than to be stopped
   */
  record Ingested(UUID itemId, List<String> fieldsNotDeclared) {}

  /**
   * One row of a CSV, after the profile has said what its columns mean.
   *
   * <p>Deliberately flat and deliberately typed: the mapping's job is to turn text into these, and
   * a row that could not be turned into one is an error the report names by line rather than a
   * value that arrives as something surprising three layers down.
   *
   * @param sourceKey what the other system called this row, or null — Homebox's {@code
   *     HB.import_ref}, InvenTree's {@code IPN}. What makes a second import of the same file an
   *     update rather than a pile of duplicates
   * @param name the item's name, the one field a row cannot omit
   * @param description its description, or null
   * @param notes free text, or null
   * @param quantity how many, defaulting to one when the column is absent
   * @param quantityUnit the unit, or null
   * @param locationPath where it is, as a path of names — {@code Garage / Shelf / Box} — or null
   *     for an item with no place
   * @param tags the tags to put on it, by name, never null
   * @param purchaseAmount what it cost, or null
   * @param purchaseCurrency the currency of that amount, from the profile
   * @param purchasedOn when it was bought, or null
   * @param purchaseSource who it was bought from, or null
   * @param warrantyUntil when the warranty runs out, or null
   * @param lifetimeWarranty whether it never does
   * @param minimumStock the level below which it counts as low, or null
   * @param attributes values for fields the type declares, by field key. A key the type does not
   *     declare is skipped and counted rather than refused: a file from another system carries
   *     columns this one has no field for, and an import that failed on the first of them would
   *     never finish
   */
  record MappedItem(
      String sourceKey,
      String name,
      String description,
      String notes,
      BigDecimal quantity,
      String quantityUnit,
      String locationPath,
      List<String> tags,
      BigDecimal purchaseAmount,
      String purchaseCurrency,
      LocalDate purchasedOn,
      String purchaseSource,
      LocalDate warrantyUntil,
      boolean lifetimeWarranty,
      BigDecimal minimumStock,
      java.util.Map<String, String> attributes) {}
}
