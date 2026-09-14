/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.catalog.api;

import de.greluc.homeinv.platform.Page;
import java.util.List;

/**
 * Reading the kinds of place a location can be.
 *
 * <h2>Why this exists</h2>
 *
 * <p>A location references a category and the column is {@code NOT NULL}, so a client that cannot
 * enumerate them cannot create a location at all — and an item that is {@code PHYSICAL} resides in
 * exactly one location (REQ-CORE-003). Until this port existed the categories were seeded per tenant
 * and reachable only by SQL, which made the stage-0 promise "an item can be created, photographed,
 * **stored** and found again" unreachable through the API.
 *
 * <p>Separate from {@link CatalogProvisioning} because they are separate concerns with separate
 * callers: provisioning is asked once, by {@code tenancy}, inside the transaction that creates a
 * tenant; this is asked on every screen that offers a picker.
 */
public interface LocationCategories {

  /**
   * One page of the tenant's location categories, in a stable order.
   *
   * <p>Paged like every other collection (REQ-NFR-010), and the shipped set is thirteen rows: the
   * first page is the whole list today.
   *
   * <p>The order is creation time then id, which is what the keyset cursor needs and is <em>not</em>
   * the order they are listed in — a tenant's categories are all written in one transaction and share
   * a timestamp to the microsecond, so the id decides and the id is random. That is deliberate rather
   * than a shortcoming: the client shows a translated label, and the only sensible order for thirteen
   * translated words is the one the reader's language sorts them in, which the server does not know.
   *
   * @param cursor an opaque cursor from a previous page, or {@code null} for the first
   * @param limit how many at most; capped at 200
   * @return the page and a cursor for the next one
   */
  Page<LocationCategoryView> list(String cursor, int limit);

}
