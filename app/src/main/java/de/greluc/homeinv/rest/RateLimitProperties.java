/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How many requests a minute each scope of {@code REQ-SEC-064} allows.
 *
 * <h2>Why three scopes and not one</h2>
 *
 * <p>Each answers a different question and none of them answers the others:
 *
 * <ul>
 *   <li><b>per user</b> — one account cannot spend the instance on its own, whether by a runaway
 *       script or by a stolen session;
 *   <li><b>per tenant</b> — one organisation cannot spend it either, across all its accounts and
 *       its service accounts, which is what the per-user limit alone would miss;
 *   <li><b>per address</b> — the caller who has no account yet is still a caller. Without it,
 *       everything before a successful login is unlimited.
 * </ul>
 *
 * <p>And a fourth, stricter one for the authentication endpoints, which is where an attacker
 * spends their requests and where an honest client makes a handful a day.
 *
 * <h2>The numbers</h2>
 *
 * <p>Chosen so that <b>no honest client meets them</b>, which is the only defensible way to set a
 * limit somebody cannot ask to have raised: opening the item list, its facets, the location tree
 * and twenty thumbnails is about thirty requests, so a person clicking as fast as they can stays an
 * order of magnitude below the per-user figure. They bound the damage a loop does; they are not a
 * pricing tier, which is {@code REQ-TEN-009}'s monthly quota and a different answer with a
 * different status code.
 *
 * @param perUserPerMinute requests one account may make
 * @param perTenantPerMinute requests one tenant may make across every account and service account
 * @param perAddressPerMinute requests one client address may make, signed in or not
 * @param authPerMinute requests one address may make against {@code /api/v1/auth/**}
 */
@ConfigurationProperties(prefix = "homeinv.rate-limit")
public record RateLimitProperties(
    Integer perUserPerMinute,
    Integer perTenantPerMinute,
    Integer perAddressPerMinute,
    Integer authPerMinute) {

  /** Applies the defaults, so that an operator configures nothing to be protected. */
  public RateLimitProperties {
    perUserPerMinute = perUserPerMinute != null ? perUserPerMinute : 600;
    perTenantPerMinute = perTenantPerMinute != null ? perTenantPerMinute : 3000;
    perAddressPerMinute = perAddressPerMinute != null ? perAddressPerMinute : 1200;
    authPerMinute = authPerMinute != null ? authPerMinute : 30;
    require(perUserPerMinute, "homeinv.rate-limit.per-user-per-minute");
    require(perTenantPerMinute, "homeinv.rate-limit.per-tenant-per-minute");
    require(perAddressPerMinute, "homeinv.rate-limit.per-address-per-minute");
    require(authPerMinute, "homeinv.rate-limit.auth-per-minute");
  }

  /**
   * Refuses a limit that would switch the protection off by looking like a setting.
   *
   * @param value what was configured
   * @param name which property, so the message names it
   * @throws IllegalArgumentException when it is not positive
   */
  private static void require(int value, String name) {
    if (value <= 0) {
      throw new IllegalArgumentException(
          name
              + " must be positive. A limit of zero refuses every request and a negative one is not"
              + " a limit; to run without rate limiting there is no setting, because there is no"
              + " deployment that should (REQ-SEC-064).");
    }
  }
}
