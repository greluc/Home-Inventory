/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.platform;

import java.util.UUID;

/**
 * What one building block removes when a tenant is erased (REQ-TEN-011, REQ-PRIV-005).
 *
 * <p>Implemented by every block that holds a tenant's data — 05 §5.9: "each block deletes its share
 * of the schema, reports completion" — and collected by {@code TenantErasureRunner}, which calls
 * each in turn.
 *
 * <p>It sits in the shared kernel rather than in {@code tenancy}, and not for convenience. Eight
 * blocks implement it and one of them is {@code authorization}, which {@code tenancy} already
 * depends on for what a role may grant; a port owned by {@code tenancy} would therefore have closed
 * a cycle between the two. The shared kernel's rule is that a type goes in when several blocks need
 * it and it holds no decision (04 §4.3) — this is an interface and a record, and the decision about
 * what an erasure does stays where the runner is.
 *
 * <p>Every implementation runs <b>inside the tenant's own context</b>, in its own transaction. It
 * therefore deletes with the ordinary policies in force and cannot touch another tenant's rows even
 * if its {@code WHERE} clause were wrong — which is the same second line every other write has.
 */
public interface TenantErasure {

  /**
   * What one block removed, or deliberately did not.
   *
   * @param block the building block's name, as 04 §4.3 spells it
   * @param rowsRemoved how many rows went
   * @param note what was left and why, or null when everything of this block's went. The one entry
   *     that always carries a note is {@code audit}: the application holds {@code INSERT} and
   *     {@code SELECT} on the log and nothing else (`REQ-SEC-069`), so the log outlives the erasure
   *     and is removed by the retention run under {@code homeinv_housekeeping}
   */
  record BlockReport(String block, long rowsRemoved, String note) {}

  /**
   * Which block this is, for the report.
   *
   * @return the building block's name
   */
  String block();

  /**
   * Where this block sits in the run: lower goes first.
   *
   * <p>Not a preference. A block whose tables carry a reference into another block's has to run
   * <b>before</b> it, because PostgreSQL refuses the other order — and a refusal halfway through
   * leaves a tenant half erased, which is the one outcome this must not have. Every implementation
   * names the foreign key that fixes its place rather than stating a number and leaving the reason
   * to be rediscovered.
   *
   * <p>The values are spaced by ten, so a block added later has somewhere to go without renumbering
   * the ones around it. Two blocks may not share one: the runner refuses to start, because an
   * ambiguous order is one that works until the classpath scan returns them the other way round.
   *
   * @return the position, lower first
   */
  int order();

  /**
   * Removes this block's share of one tenant.
   *
   * <p>Called with the tenant context already established and inside a transaction. It must be safe
   * to call twice: a run interrupted between two blocks is resumed by the next one, and a block that
   * has nothing left to remove reports zero rather than failing.
   *
   * @param tenantId the tenant being erased, for logging and for assertions — the context is already
   *     set, so a query needs no tenant predicate of its own
   * @return what was removed
   */
  BlockReport erase(UUID tenantId);
}
