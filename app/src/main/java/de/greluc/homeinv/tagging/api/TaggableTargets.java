/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.tagging.api;

import java.util.UUID;

/**
 * What the blocks that own taggable rows can tell {@code tagging} about one of them.
 *
 * <h2>Why the interface lives here and the implementations do not</h2>
 *
 * <p>A tag is assigned to an item or to a place, and neither row belongs to this block.
 * {@code tagging} may not read {@code inventory}'s or {@code locations}' schema (04 §4.5), so
 * without this port it cannot tell a target that is missing from one that is merely not tagged yet —
 * and an assignment naming a row that is not there reaches the database as a foreign-key violation.
 * That answered {@code 500} where the endpoint promised {@code 404}, and it was the reason
 * {@code BulkEntryRunner} had to read the item itself before every bulk tag.
 *
 * <p>The same shape as {@link de.greluc.homeinv.catalog.api.AttributeUsage}, for the same reason:
 * the block that owns the rows answers the question, and the dependency still runs one way. Asking
 * {@code inventory} or {@code locations} directly from here would close a cycle.
 *
 * <h2>What "visible" means</h2>
 *
 * <p>Exactly what a {@code GET} on that resource would answer. A row of another tenant, a row in the
 * trash and a row that never existed are one case to a caller and must stay one case here
 * (REQ-SEC-025): every implementation reads under row-level security and the tenant's
 * own location scope, so the three are already indistinguishable by the time the answer is formed.
 */
public interface TaggableTargets {

  /**
   * Which kind of thing this implementation speaks for.
   *
   * <p>{@code tagging} holds every implementation and picks by this, rather than each block
   * registering itself somewhere. A kind with no implementation is a wiring fault and fails loudly
   * the first time somebody tags one.
   *
   * @return the target kind this answers for
   */
  TagService.TagTarget kind();

  /**
   * Refuses a target this tenant cannot see.
   *
   * <p>Called before an assignment is written, before one is removed, and before a target's tags are
   * listed — so all three answer the same way about the same row.
   *
   * @param targetId the item or place a caller named
   * @throws de.greluc.homeinv.platform.NotFoundException when this tenant has no such live row
   */
  void requireVisible(UUID targetId);
}
