/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: Apache-2.0
 */
package de.greluc.homeinv.plugin.api.port;

import de.greluc.homeinv.plugin.api.CallContext;
import de.greluc.homeinv.plugin.api.MoneyValue;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

/**
 * Estimates what an item is worth now, and what replacing it would cost (09 §9.2).
 *
 * <p>Straight-line depreciation is in the core image — it is arithmetic and opens nothing.
 * Declining balance is the same. Market-price and dealer services are plugins, because they call
 * out.
 *
 * <p>Both numbers are estimates and the core presents them as estimates: a current value is what
 * the thing is worth today, a replacement value is what buying it again would cost, and an
 * insurance conversation needs the second. Neither is a valuation anybody is entitled to rely on,
 * and {@link Valuation#basis()} is what makes that visible rather than implied.
 *
 * <p>Stage 1 (REQ-CORE-021).
 */
public interface ValuationProvider {

  /**
   * The key this provider is known by, lowercase and stable — {@code straight-line}, {@code
   * declining-balance}.
   *
   * @return the key
   */
  String providerKey();

  /**
   * Estimates an item's value.
   *
   * @param context who is asking
   * @param subject what is known about the item
   * @return the estimate, or empty when this provider has nothing to say about this item — a
   *     market-price provider that has never seen this model, a depreciation provider with no
   *     purchase price to depreciate. Empty is an answer and not a failure
   * @throws de.greluc.homeinv.plugin.api.PluginException when a source could not be reached
   */
  Optional<Valuation> value(CallContext context, Subject subject);

  /**
   * What is known about the item being valued.
   *
   * @param itemId the core's id, so that a provider can cache by it. It identifies nothing outside
   *     this instance
   * @param typeKey the item type's key, for example {@code tool} or {@code book}
   * @param purchasePrice what it cost, or empty when nobody recorded it
   * @param purchasedOn when it was bought, or {@code null}
   * @param conditionKey the condition as the tenant's own vocabulary spells it, or empty. A
   *     provider that does not know a tenant's vocabulary ignores it rather than guessing
   * @param attributes the item's attributes, so that a market-price provider can find the model.
   *     Only the fields the tenant made visible to plugins are in here — a {@code sensitive} field
   *     never leaves the core (REQ-SEC-011)
   */
  record Subject(
      String itemId,
      String typeKey,
      MoneyValue purchasePrice,
      LocalDate purchasedOn,
      String conditionKey,
      Map<String, String> attributes) {}

  /**
   * What an item is estimated to be worth.
   *
   * @param currentValue what it is worth today
   * @param replacementValue what buying it again would cost, or {@code null} when the provider has
   *     no basis for one. Not silently equal to the current value: for anything second-hand the two
   *     differ, and quietly reporting the same number twice would be a made-up figure in an
   *     insurance claim
   * @param basis how it was arrived at, in one short English phrase — {@code straight-line over 5
   *     years}, {@code median of 14 completed sales}. Shown next to the number, because an
   *     estimate whose basis is invisible reads as a fact
   * @param asOf what moment the estimate speaks for. A market estimate ages; a depreciation does
   *     not
   * @param confidence how sure the provider is, 0 to 100. A deterministic calculation says 100
   */
  record Valuation(
      MoneyValue currentValue,
      MoneyValue replacementValue,
      String basis,
      Instant asOf,
      int confidence) {}
}
