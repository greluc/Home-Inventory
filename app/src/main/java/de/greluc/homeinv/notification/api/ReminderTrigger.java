/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.notification.api;

/**
 * What a reminder rule watches (REQ-NOTI-003).
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>REQ-NOTI-001 describes a rule as "a saved search, a time offset and a channel", and an offset
 * has to be an offset <i>from</i> something. The filter grammar of 08 §8.2 has no relative dates —
 * {@code warrantyUntil:lte:2026-10-01} is an absolute date that stops being true tomorrow — so a
 * saved search alone cannot express "expires within a fortnight" in a way that rolls forward. This
 * names which date, or which condition. The saved search keeps its own job: narrowing <i>which</i>
 * things the rule watches.
 *
 * <p>The set is REQ-NOTI-003's own list and is closed, mirrored by a check constraint on {@code
 * notification.notification_rule.trigger_kind} and compared against it by {@code
 * ReminderTriggerTest} — the treatment {@code problem-types.yaml} gets, because a value that
 * reaches a stored row outlives the constant that produced it.
 *
 * <h2>Two of them have no source yet</h2>
 *
 * <p>A trigger is declared here and <b>served</b> by a {@link ReminderSource} in the block that
 * owns the data. {@link #STOCKTAKE_DISCREPANCY} is the one declared and served by nothing: a
 * stocktake is stage 2. A rule naming it is refused when it is <b>created</b>, by the person who
 * can still choose another — rather than accepted and then silently never firing, which is the
 * failure a reminder feature cannot afford.
 */
public enum ReminderTrigger {

  /**
   * A warranty is about to run out (REQ-LIFE-002, REQ-LIFE-013).
   *
   * <p>Watches {@code inventory.item.warranty_until}. An item with a lifetime warranty has no date
   * and is never due — which is the reason REQ-LIFE-002 made it a flag rather than a date far in
   * the future.
   */
  WARRANTY_EXPIRY("item"),

  /**
   * A thing is due for servicing (REQ-LIFE-004).
   *
   * <p>Watches {@code inventory.item.maintenance_interval_days} against the last maintenance entry
   * recorded for the item, so "every twelve months" is measured from the last service rather than
   * from a fixed calendar: servicing something early moves the next reminder instead of leaving it
   * where it was. A thing that has never been serviced counts from its purchase date, and from the
   * day its record was created when even that is unknown.
   */
  MAINTENANCE_DUE("item"),

  /**
   * A lent thing is due back, or is overdue (REQ-LIFE-006).
   *
   * <p>Watches the open loans' {@code due_on}. A loan lent with no date agreed is never due: there
   * is nothing for it to be late against, and treating "no date" as "due now" would make a reminder
   * out of a lend nobody put a date on.
   */
  LOAN_DUE("loan"),

  /**
   * A consumable has run down (REQ-CORE-016).
   *
   * <p>The one trigger with no date of its own: the condition is {@code quantity <= minimum_stock},
   * and the offset does not apply to it. A rule naming it is accepted with any offset and the
   * offset is ignored, because refusing one would be refusing a rule that is otherwise correct.
   */
  MINIMUM_STOCK("item"),

  /**
   * Something with a date on it is about to run out (REQ-NOTI-003, REQ-LIFE-013, REQ-CORE-023).
   *
   * <p>Served by {@code ExpiryFieldReminders} since 2026-09-20. It was declared and served by
   * nothing before that, and the reason was true at the time: nothing stored such a date, and a
   * trigger reading a column that does not exist is a rule that never fires. The {@code expiry}
   * flag on a field definition is that column — a tenant marks one of its own date fields as an
   * expiry and this watches every item that has one.
   *
   * <p>Which makes it broader than its name: a passport, an inspection, a certificate, a tin of
   * paint. The name stays because REQ-NOTI-003 uses it and a rule already written against it would
   * otherwise stop meaning anything.
   */
  LICENCE_EXPIRY("item"),

  /**
   * A stocktake found something out of place (REQ-LIFE-010, REQ-NOTI-003).
   *
   * <p><b>Declared and not served.</b> Stocktaking is stage 2.
   */
  STOCKTAKE_DISCREPANCY("stocktake");

  private final String subjectKind;

  ReminderTrigger(String subjectKind) {
    this.subjectKind = subjectKind;
  }

  /**
   * What kind of thing this trigger reminds about.
   *
   * <p>Stored beside the subject's id in {@code notification.reminder}, so a record says what it was
   * about without a column per kind. An item today, a stocktake run tomorrow.
   *
   * @return the subject kind, as the reminder record spells it
   */
  public String subjectKind() {
    return subjectKind;
  }

  /**
   * Whether the offset means anything for this trigger.
   *
   * <p>False for {@link #MINIMUM_STOCK}, which is a condition rather than a date: "three days
   * before the coffee runs out" is not a thing anybody can know.
   *
   * @return whether the rule's {@code offsetDays} is applied
   */
  public boolean isDated() {
    return this != MINIMUM_STOCK;
  }
}
