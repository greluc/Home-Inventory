/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.notification.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What is due, according to the block that owns the data (REQ-NOTI-003).
 *
 * <h2>An outbound port, which is what keeps the boundary</h2>
 *
 * <p>{@code notification} must not read {@code inventory}'s tables — no building block reaches
 * another's schema (ADR-0002, REQ-NFR-019…024). So it declares what it needs <b>here</b>, in its
 * own published package, and the block that holds the dates implements it. The dependency points
 * from {@code inventory} to {@code notification.api} and never back, so there is no cycle for
 * Spring Modulith to refuse.
 *
 * <p>It is also why a trigger can be added without touching the scheduler: a new implementation of
 * this port registers itself as a bean and the run picks it up.
 *
 * <p>The direction is easy to break by accident and was: the run narrows a rule by its saved
 * search, and reading ids out of a {@code Page<ItemView>} made {@code notification} name an {@code
 * inventory} type — a cycle, for a field it immediately discarded. {@code
 * SavedSearches.matchingIds} exists because of it. A port pointing one way is a claim the module
 * check tests rather than one this comment can make.
 *
 * <h2>The source does not know about rules</h2>
 *
 * <p>It answers one question — "what of mine is due by this date" — and knows nothing about
 * offsets, channels, saved searches or what has already been raised. The scheduler subtracts the
 * offset before asking, narrows the answer by the rule's saved search afterwards, and refuses to
 * raise what it has raised before. A source that knew any of that would have to be changed every
 * time the rule model did.
 */
public interface ReminderSource {

  /**
   * Which trigger this source serves.
   *
   * <p>One source per trigger. Two beans claiming the same one is a configuration error and fails
   * the run rather than letting one of them win silently.
   *
   * @return the trigger
   */
  ReminderTrigger trigger();

  /**
   * What is due on or before a date, for the tenant in the current context.
   *
   * <p>Called inside a tenant context, so the implementation reads its own tables under the
   * ordinary row-level security and needs no tenant argument — the same shape every other
   * tenant-scoped read in this codebase has.
   *
   * @param by the date to judge against. The scheduler has already applied the rule's offset, so a
   *     source compares its own dates against this one and nothing else
   * @param limit how many at most, so one overdue backlog cannot produce an unbounded run
   * @return what is due, in no particular order; empty when nothing is
   */
  List<Due> dueBy(LocalDate by, int limit);

  /**
   * One thing that is due.
   *
   * @param subjectId what it is about — an item, a loan
   * @param itemId the item to name in the message, which for a loan is the thing that was lent
   *     rather than the loan itself. Equal to {@code subjectId} where the subject is the item
   * @param dueOn the date it is due. Recorded on the reminder so that moving the date raises a new
   *     reminder and leaving it alone does not
   * @param label what to call it in the message — an item's name, in the tenant's own words. The
   *     source supplies it because it owns the row, and the scheduler must not read it back across
   *     a boundary to build a sentence
   */
  record Due(UUID subjectId, UUID itemId, LocalDate dueOn, String label) {}
}
