/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.api;

import jakarta.annotation.Nullable;
import de.greluc.homeinv.platform.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What an insurer asks for (REQ-LIFE-016).
 *
 * <h2>Replacement value, and only replacement value</h2>
 *
 * <p>Not what a thing cost and not what it is worth now. An insurer asks what buying it again would
 * cost, which is `REQ-LIFE-014`'s separate column and is never derived from the other two — for
 * anything second-hand the three differ, and quietly reporting one of them three times would be a
 * made-up figure in a claim.
 *
 * <p>Per room and in total, per currency, never mixed (`REQ-LIFE-017`). A household that bought a
 * camera in dollars gets two lines and a statement that nothing was converted.
 *
 * <h2>Why the evidence is in the report and not a link to it</h2>
 *
 * <p>Each line carries the primary photograph and the receipt, because the document is filed after
 * a fire and the thing that burned may well have been the machine with the links in it. A report
 * that says "see your inventory" is a report that assumes the inventory is reachable.
 */
public interface InsuranceReport {

  /**
   * The report for a place and everything under it.
   *
   * @param root the place to report on, or {@code null} for everything the tenant has
   * @param limit how many items at most; capped at 200 (REQ-NFR-010)
   * @return the report
   * @throws de.greluc.homeinv.platform.NotFoundException when this tenant has no such location
   */
  Report of(UUID root, int limit);

  /**
   * One report.
   *
   * @param producedOn the day it was made, which belongs on a document somebody files
   * @param rooms one per place holding anything, deepest path first so a reader walks the house in
   *     order
   * @param totals the whole report's replacement value, per currency
   * @param converted always {@code false}, and present rather than implied: `REQ-LIFE-017` asks the
   *     report to <b>state</b> that no conversion took place, and a field a client can render is a
   *     statement where a missing field is an assumption
   * @param withoutAReplacementValue how many items were left out because nobody has recorded what
   *     replacing them would cost. Counted rather than hidden: an insurance report that quietly
   *     omits half a household is worse than one that says it did
   */
  record Report(
      LocalDate producedOn,
      List<Room> rooms,
      List<Money> totals,
      boolean converted,
      long withoutAReplacementValue) {}

  /**
   * One place and what is in it.
   *
   * @param locationId the place
   * @param path what to call it, as a person would say it — {@code House / Garage / Shelf}
   * @param lines the things directly in it
   * @param totals what they would cost to replace, per currency
   */
  record Room(UUID locationId, String path, List<Line> lines, List<Money> totals) {}

  /**
   * One thing.
   *
   * @param itemId the item
   * @param name what it is called
   * @param quantity how many, because two of a thing cost twice as much to replace
   * @param replacement what replacing it would cost
   * @param asOf the day that figure was true, which is what makes it worth anything to a reader
   * @param source who said so — {@code MANUAL} or {@code PLUGIN}
   * @param photo the primary photograph, or null when it has none
   * @param receipts every attachment the tenant marked as a receipt or a warranty proof
   */
  record Line(
      UUID itemId,
      String name,
      java.math.BigDecimal quantity,
      Money replacement,
      LocalDate asOf,
      String source,
      @Nullable Evidence photo,
      List<Evidence> receipts) {}

  /**
   * A file that backs a line up.
   *
   * @param mediaObjectId which file
   * @param mediaType what it is
   * @param byteSize how large
   * @param role what the tenant said it was for
   */
  record Evidence(UUID mediaObjectId, String mediaType, long byteSize, String role) {}
}
