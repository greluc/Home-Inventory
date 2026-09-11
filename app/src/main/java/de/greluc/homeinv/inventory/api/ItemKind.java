/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.api;

/**
 * Whether an item exists in the physical world.
 *
 * <p>In the published package, not in {@code domain}: it appears in
 * {@link ItemService.CreateItemCommand}, and a type in a published signature is published
 * whether or not it was meant to be.
 *
 * <p>The distinction is not cosmetic: it decides whether the item must have a location, whether it
 * can carry a printed code, and whether a stocktake can find it. The database carries the same two
 * values as a check constraint, so a row written outside this application obeys the same rule.
 */
public enum ItemKind {
  /** Something you can pick up. Resides in exactly one location (REQ-CORE-003). */
  PHYSICAL,

  /** A licence, a file, a subscription. Has no location and is never scanned. */
  DIGITAL
}
