/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.application;

import de.greluc.homeinv.catalog.api.TypeRegistry;
import de.greluc.homeinv.inventory.infrastructure.DepreciationQueries;
import de.greluc.homeinv.platform.TenantContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Straight-line depreciation, the one valuation that ships with the product (REQ-LIFE-009).
 *
 * <h2>What it computes</h2>
 *
 * <p>A thing loses the same amount every month over the useful life its <b>type</b> declares, and
 * is worth nothing once that life is over. Purchase price times the fraction of the life still to
 * run. Nothing cleverer: a straight line is the calculation everybody already understands, and the
 * point of the {@code ValuationProvider} port is that anybody wanting a market estimate installs a
 * plugin that does one properly.
 *
 * <h2>What it will not touch</h2>
 *
 * <p>Only rows whose {@code current_source} is {@code DEPRECIATION} or empty. A figure somebody
 * <b>typed</b> is the figure they will be asked to justify, and a run that overwrote it overnight
 * would be the application disagreeing with its owner in silence. A figure a <b>plugin</b>
 * produced is not this one's to correct either.
 *
 * <h2>Nothing happens until a tenant says how long something lasts</h2>
 *
 * <p>No shipped type carries a useful life, decided with the owner on 2026-09-20: how long a
 * household's furniture lasts is a judgement about that household, and inventing one here would be
 * putting this application's guess into somebody's insurance report. So an instance nobody has
 * configured depreciates nothing, and that is the honest state rather than a default in disguise.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DepreciationRefresh {

  private final DepreciationQueries items;
  private final TypeRegistry types;

  /**
   * Recomputes every depreciated value in the current tenant.
   *
   * @param on the day to value as of, which a test pins and a run takes as today
   * @return how many items were written
   */
  @Transactional
  public int refresh(LocalDate on) {
    List<DepreciationQueries.Depreciable> candidates = items.depreciable();
    if (candidates.isEmpty()) {
      return 0;
    }
    Map<UUID, Integer> lives =
        types.usefulLivesOfVersions(
            candidates.stream().map(DepreciationQueries.Depreciable::typeVersionId).distinct()
                .toList());

    int written = 0;
    for (DepreciationQueries.Depreciable item : candidates) {
      Integer months = lives.get(item.typeVersionId());
      if (months == null) {
        // The type has no useful life -- it never had one, or somebody has just
        // taken it away. A value this run wrote earlier is now a figure nothing
        // stands behind, so it goes rather than lingering as the last thing a
        // rule that no longer exists said.
        if (item.hasDepreciatedValue()) {
          items.clear(item.itemId());
          written++;
        }
        continue;
      }
      BigDecimal value = valueOn(item.purchaseAmount(), item.purchasedOn(), months, on);
      items.write(item.itemId(), value, item.purchaseCurrency(), on);
      written++;
    }
    log.debug("Depreciation refreshed {} item(s) in tenant {}", written, TenantContext.require());
    return written;
  }

  /**
   * What a thing is worth, straight-line, on a given day.
   *
   * <p>Whole months elapsed, not days: a useful life is stated in months and a value that moved
   * every morning would be a figure nobody could quote twice. Never below zero and never above the
   * purchase price — a thing bought in the future is worth what it cost, which is the sensible
   * reading of a typo rather than a reason to refuse the whole run.
   *
   * @param purchase what it cost
   * @param purchasedOn when
   * @param usefulLifeMonths how long its type says it lasts
   * @param on the day to value it on
   * @return the value, with the scale the money column stores
   */
  static BigDecimal valueOn(
      BigDecimal purchase, LocalDate purchasedOn, int usefulLifeMonths, LocalDate on) {
    long elapsed = Math.max(0, ChronoUnit.MONTHS.between(purchasedOn, on));
    long remaining = Math.max(0, usefulLifeMonths - elapsed);
    return purchase
        .multiply(BigDecimal.valueOf(remaining))
        .divide(BigDecimal.valueOf(usefulLifeMonths), 4, RoundingMode.HALF_UP);
  }
}
