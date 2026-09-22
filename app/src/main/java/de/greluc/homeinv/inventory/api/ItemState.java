/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.inventory.api;

/**
 * Where an item is in its life (04 §4.4).
 *
 * <h2>One value, and it is the one sync orders</h2>
 *
 * <p>The machine the architecture describes: {@code ACTIVE → LENT → ACTIVE}, {@code ACTIVE →
 * ARCHIVED}, {@code ACTIVE → TRASHED → ACTIVE}, {@code ACTIVE → SOLD}/{@code DISPOSED}. It is
 * stored in one column, {@code inventory.item.lifecycle_state}, because 11 §11.3 resolves a sync
 * conflict by <b>ranking</b> these values against each other — {@code DISPOSED} beats {@code
 * TRASHED} beats {@code LENT} — and a rank needs one ordered value rather than a state spread
 * across several columns.
 *
 * <p>{@code deleted_at} stays <b>beside</b> it rather than being replaced by it: the state says
 * <i>what</i>, the timestamp says <i>when</i>, and the retention run of REQ-CORE-009 needs the
 * second. The two are written together in {@link
 * de.greluc.homeinv.inventory.domain.Item#markDeleted}, so a listing showing the state and a query
 * filtering on the timestamp cannot disagree.
 *
 * <h2>PURGED is not here</h2>
 *
 * <p>04 §4.4 ends {@code TRASHED → PURGED}, and a purge is a {@code DELETE}: there is no row left
 * to carry the value. What survives is the revision history, which outlives the item deliberately.
 * A value for it would be one no row could ever hold.
 */
public enum ItemState {

  /** In the inventory and available. The state a new item is created in. */
  ACTIVE,

  /**
   * Somebody else has it (REQ-LIFE-005).
   *
   * <p>Set when a loan is opened and cleared when it is returned. The loan row holds <i>who</i> and
   * <i>since when</i>; this says <i>that</i>, which is what a listing shows and what sync ranks.
   */
  LENT,

  /**
   * Kept on the books but out of everyday use.
   *
   * <p>Named by 04 §4.4 and by 11 §11.3's precedence, and <b>nothing reaches it yet</b>: no
   * requirement describes archiving an item, and REQ-CORE-046's archiving is about a location. It
   * is declared because the state machine declares it, not because there is a hidden way in.
   */
  ARCHIVED,

  /**
   * In the trash, recoverably (REQ-CORE-009).
   *
   * <p>The first stage of the two-stage deletion; {@code deleted_at} carries when.
   */
  TRASHED,

  /** Sold (REQ-LIFE-007). What it fetched, when and to whom are columns on the item. */
  SOLD,

  /** Thrown away, given away or otherwise gone (REQ-LIFE-007). */
  DISPOSED;

  /**
   * Whether an item in this state is still something the tenant owns and can act on.
   *
   * <p>True for {@link #ACTIVE} and {@link #LENT} — a lent thing is still yours — and false once it
   * has been archived, trashed or parted with. Used where a refusal has to distinguish "you have
   * this" from "you had this".
   *
   * @return whether the item is still in the tenant's possession in the ordinary sense
   */
  public boolean isHeld() {
    return this == ACTIVE || this == LENT;
  }

  /**
   * Whether the item has left the inventory for good (REQ-LIFE-007).
   *
   * <p>{@link #SOLD} and {@link #DISPOSED} are terminal: unlike a trashing there is no way back,
   * because the thing itself is gone rather than the record of it.
   *
   * @return whether this is an end state
   */
  public boolean isGoneForGood() {
    return this == SOLD || this == DISPOSED;
  }
}
