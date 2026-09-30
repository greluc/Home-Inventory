/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.notification.api;

import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Reminder rules, as data (REQ-NOTI-001).
 *
 * <p>A rule is four things: <b>what</b> to watch ({@link ReminderTrigger}), <b>which</b> of them
 * (an optional saved search), <b>when</b> relative to the date ({@code offsetDays}) and <b>where</b>
 * to send it (a channel key). 04 §4.4 calls this level 1 — declarative data and not code — which is
 * the same line ADR-0020 draws around the type system: anything that needs a condition the rule
 * model cannot express is a plugin, not a new kind of rule.
 *
 * <p>Nothing here sends anything. The scheduler reads these rules, asks each {@link ReminderSource}
 * what is due, and raises an ordinary notification through {@link Notifications} — so a reminder
 * reaches somebody on the channels they subscribed to, and is retried and logged like everything
 * else.
 */
public interface ReminderRules {

  /**
   * The tenant's rules, oldest first.
   *
   * @return the rules, enabled and disabled alike — a disabled one keeps what was written, so
   *     turning it back on does not ask for it again
   */
  List<ReminderRuleView> list();

  /**
   * One rule.
   *
   * @param id which one
   * @return it
   * @throws de.greluc.homeinv.platform.NotFoundException when this tenant has no such rule
   */
  ReminderRuleView get(UUID id);

  /**
   * Creates a rule.
   *
   * @param command what to watch, which of them, when and where
   * @param actor who is creating it
   * @return the stored rule
   * @throws UnservedTriggerException when nothing can answer that trigger yet — refused here, by
   *     the person who can still choose another, rather than accepted and then never firing
   * @throws de.greluc.homeinv.platform.NotFoundException when the saved search is not this
   *     tenant's
   */
  ReminderRuleView create(NewReminderRule command, UUID actor);

  /**
   * Replaces a rule.
   *
   * @param id which one
   * @param command what it should now say
   * @param expectedVersion the version the caller acted on; empty skips the check (REQ-API-004)
   * @param actor who is changing it
   * @return the stored rule
   * @throws UnservedTriggerException when nothing can answer that trigger yet
   * @throws de.greluc.homeinv.platform.NotFoundException when this tenant has no such rule
   * @throws de.greluc.homeinv.platform.StaleVersionException when somebody else changed it first
   */
  ReminderRuleView update(
      UUID id, NewReminderRule command, java.util.OptionalLong expectedVersion, UUID actor);

  /**
   * Removes a rule outright.
   *
   * <p>Outright rather than tombstoned, for the reason REQ-SRCH-008 removes a saved search that
   * way: nothing points at a reminder rule. Its {@code notification.reminder} records go with it —
   * they are the rule's own bookkeeping about what it had already said, and they mean nothing
   * without it. Notifications that have already gone out are unaffected: they are rows of their
   * own, and a reminder that was delivered happened.
   *
   * @param id which one
   * @param actor who is removing it
   * @throws de.greluc.homeinv.platform.NotFoundException when this tenant has no such rule
   */
  void remove(UUID id, UUID actor);

  /**
   * Which triggers this installation can actually answer (REQ-NOTI-003).
   *
   * <p>Derived from the registered {@link ReminderSource}s rather than listed anywhere, so a
   * trigger becomes writable the moment a block serves it. A surface offering a choice asks this
   * rather than showing all six — two of them are refused on save, and a form that offers a rule
   * which cannot be saved is a form that wastes somebody's time.
   *
   * @return the triggers a rule may name here
   */
  java.util.Set<ReminderTrigger> servedTriggers();

  /**
   * What a rule should say.
   *
   * @param name what a person calls it
   * @param trigger which date or condition it watches
   * @param savedSearchId which things to watch, or {@code null} for everything the trigger finds
   * @param offsetDays days relative to the trigger's date: <b>positive warns early</b> (14 is a
   *     fortnight before a warranty ends), <b>negative waits until after</b> (-1 fires the day
   *     after a loan was due), zero fires on the day. Ignored for a trigger that is not dated
   * @param channelKey which channel, as a plugin manifest names it
   * @param enabled whether it fires
   */
  record NewReminderRule(
      String name,
      ReminderTrigger trigger,
      UUID savedSearchId,
      int offsetDays,
      String channelKey,
      boolean enabled) {}

  /**
   * A stored rule.
   *
   * @param id its id
   * @param name what a person calls it
   * @param trigger which date or condition it watches
   * @param savedSearchId which things it watches, or {@code null} for all of them
   * @param offsetDays days relative to the trigger's date
   * @param channelKey which channel
   * @param enabled whether it fires
   * @param version for {@code If-Match}
   * @param updatedAt when it last changed
   */
  record ReminderRuleView(
      UUID id,
      String name,
      ReminderTrigger trigger,
      @Nullable UUID savedSearchId,
      int offsetDays,
      String channelKey,
      boolean enabled,
      long version,
      Instant updatedAt) {}
}
