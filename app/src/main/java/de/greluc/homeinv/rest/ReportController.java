/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import de.greluc.homeinv.authorization.api.Permission;
import de.greluc.homeinv.authorization.api.RequiresPermission;
import de.greluc.homeinv.inventory.api.ExpiryOverview;
import de.greluc.homeinv.inventory.api.ValuationReport;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reports (REQ-LIFE-008, REQ-LIFE-015, REQ-LIFE-017).
 *
 * <p>One endpoint with a dimension rather than three endpoints, because a client switching between
 * "by room" and "by tag" is changing a parameter and not navigating somewhere else — and the answer
 * has the same shape whichever it asks for.
 *
 * <p>{@code ITEM_READ} and no report permission of its own: a total is a sum of things the caller
 * can already see, and a permission that let somebody read the sum without the parts would be a
 * disclosure rather than a restriction.
 */
@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
public class ReportController {

  private final ValuationReport reports;
  private final ExpiryOverview expiries;

  /**
   * What the things in here are worth (REQ-LIFE-008).
   *
   * <p>Three figures per row, each per currency and never mixed — REQ-LIFE-017 forbids a
   * mixed-currency total "not even as an approximation", so a set spanning two currencies is two
   * lines and the answer says {@code converted: false} rather than leaving it to be assumed.
   *
   * <p>A location row carries <b>both</b> its own total and its subtree's, labelled: a room holds
   * boxes, and "what is this room worth" and "what is sitting directly in this room" are different
   * questions. For a type or a tag there is no tree and the two are equal.
   *
   * @param by {@code location}, {@code type} or {@code tag}
   * @param root for {@code by=location} only: the place to report on, or omitted for the whole
   *     tenant. Ignored for the other dimensions rather than refused, because a client switching
   *     the dimension should not have to clear it
   * @param limit how many rows at most; capped at 200
   * @return the report
   */
  @GetMapping(path = "/valuation", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.ITEM_READ)
  @CanFail({ProblemType.NOT_FOUND, ProblemType.MALFORMED_REQUEST, ProblemType.VALIDATION_FAILED})
  public ValuationReport.Report valuation(
      @RequestParam @NotNull Dimension by,
      @RequestParam(required = false) UUID root,
      @RequestParam(required = false, defaultValue = "50") @Positive @Max(200) int limit) {
    return switch (by) {
      case LOCATION -> reports.byLocation(root, limit);
      case TYPE -> reports.byType(limit);
      case TAG -> reports.byTag(limit);
    };
  }

  /**
   * Everything that runs out, soonest first (REQ-LIFE-013).
   *
   * <p>Warranty ends, licence expiries and best-before dates in one list. The first is a column on
   * the item; the other two are type attributes whose field is marked {@code expiry}, which is what
   * lets a tenant's own date join the list without this endpoint knowing its name.
   *
   * <p>Dates that have already gone are included <b>by default</b>: a warranty that ran out last
   * month is the one somebody most wants to know about, and an overview that quietly dropped it
   * would forget its own point.
   *
   * @param upTo the last date to include, or omitted for everything. A caller wanting the next
   *     fortnight passes today plus fourteen
   * @param includePast whether to include what has already run out; {@code true} unless said
   *     otherwise
   * @param limit how many at most; capped at 200
   * @return the entries, soonest first
   */
  @GetMapping(path = "/expiries", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.ITEM_READ)
  @CanFail({ProblemType.MALFORMED_REQUEST, ProblemType.VALIDATION_FAILED})
  public List<ExpiryOverview.Expiring> expiries(
      @RequestParam(required = false)
          @org.springframework.format.annotation.DateTimeFormat(iso =
              org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
          java.time.LocalDate upTo,
      @RequestParam(required = false, defaultValue = "true") boolean includePast,
      @RequestParam(required = false, defaultValue = "50") @Positive @Max(200) int limit) {
    return expiries.due(upTo, includePast, limit);
  }

  /**
   * What a valuation report may be grouped by (REQ-LIFE-008).
   *
   * <p>An enum and not a free string: the requirement names three dimensions, and a fourth would be
   * a code change rather than a parameter. A value outside it is a {@code 422} naming the field,
   * which is what a caller can act on.
   */
  public enum Dimension {
    /** Per place, with each row carrying its own total and its subtree's. */
    LOCATION,
    /** Per item type, merged across the type's versions — an item written last year is still a
     * power tool. */
    TYPE,
    /** Per tag. Rows overlap, because a thing can carry several tags. */
    TAG
  }
}
