/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Everything that runs out, in one list (REQ-LIFE-013).
 *
 * <h2>Two sources, one list</h2>
 *
 * <p>A warranty end is a <b>column</b> on the item ({@code warranty_until}, REQ-LIFE-002, made a
 * column by the argument in 07 §7.13). A licence expiry and a best-before date are <b>type
 * attributes</b> — {@code expiresOn} and {@code bestBefore} in the shipped templates. This reads
 * both and sorts them together, because "what runs out next" is one question and a person asking it
 * does not care which of the two places the date lives in.
 *
 * <h2>Which attributes count</h2>
 *
 * <p>The ones whose field is marked {@code expiry}, which is a fifth flag beside {@code
 * searchable}, {@code sortable}, {@code facetable} and {@code sensitive} (REQ-CORE-023). Decided
 * with the owner on 2026-09-20, and the alternatives are both worse in a way 07 §7.13 already
 * described: collecting every date-typed attribute puts "last calibrated" in an expiry list, and
 * hard-coding the two shipped keys makes a tenant's own {@code validUntil} vanish silently.
 *
 * <p>A lifetime warranty contributes nothing — it has no date, which is exactly why REQ-LIFE-002
 * made it a flag rather than a date far in the future.
 */
public interface ExpiryOverview {

  /**
   * What runs out, soonest first (REQ-LIFE-013).
   *
   * <p>Sorted by due date, which is the requirement's own acceptance criterion and the only order
   * this list has a reason to be in.
   *
   * @param upTo the last date to include, or {@code null} for everything that has a date at all.
   *     A caller wanting "the next fortnight" passes today plus fourteen
   * @param includePast whether to include dates that have already gone. {@code true} by default at
   *     the surface: a warranty that ran out last month is the one somebody most wants to know
   *     about, and leaving it out would make the overview quietly forget its own point
   * @param limit how many at most; capped at 200 (REQ-NFR-010)
   * @return the entries, soonest first
   */
  List<Expiring> due(LocalDate upTo, boolean includePast, int limit);

  /**
   * One thing that runs out.
   *
   * @param itemId which item
   * @param itemName what it is called
   * @param kind {@code warranty} for the column, or the field's key for an attribute — so a client
   *     can group by it and a person can see <i>what</i> runs out rather than only when
   * @param label what to call the kind in the tenant's language, or the field key when the field
   *     declares no label
   * @param dueOn when it runs out
   */
  record Expiring(UUID itemId, String itemName, String kind, String label, LocalDate dueOn) {}
}
