/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.notification.application;

import de.greluc.homeinv.notification.api.ReminderRules.ReminderRuleView;
import de.greluc.homeinv.notification.api.ReminderScope;
import de.greluc.homeinv.notification.api.ReminderSource;
import de.greluc.homeinv.notification.api.ReminderTrigger;
import de.greluc.homeinv.notification.infrastructure.NotificationQueries;
import de.greluc.homeinv.notification.infrastructure.ReminderRuleQueries;
import de.greluc.homeinv.platform.TenantContext;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns reminder rules into notifications (REQ-NOTI-001, REQ-NOTI-003).
 *
 * <h2>What a pass does</h2>
 *
 * <ol>
 *   <li>reads the tenant's enabled rules;
 *   <li>for each, asks the {@link ReminderSource} that serves its trigger what is due by
 *       <i>today plus the rule's offset</i> — the offset is applied to the <b>question</b>, so a
 *       source never needs to know that rules have offsets at all;
 *   <li>narrows the answer by the rule's saved search, when it has one;
 *   <li>records each reminder, and raises a notification only for the ones this pass recorded.
 * </ol>
 *
 * <h2>Once, and the database decides</h2>
 *
 * <p>Step four is the whole idempotency: {@link ReminderRuleQueries#recordRaised} inserts and
 * catches the duplicate, so two overlapping passes cannot both send. A read-then-write would find
 * nothing recorded in both and send twice — and a reminder feature that repeats itself is one
 * people switch off, which costs more than one that occasionally arrives late.
 *
 * <h2>Why the run reads no foreign table</h2>
 *
 * <p>It asks a port. {@code notification} must not read {@code inventory}'s rows (ADR-0002), and
 * {@link ReminderSource} is how the block that owns the dates answers without either side reaching
 * into the other. The source even supplies the label for the message, so this class never has to
 * read a name back across a boundary to build a sentence.
 */
@Slf4j
@Component
public class ReminderRunner {

  /** How many due things one rule may raise in one pass, so a backlog cannot run unbounded. */
  private static final int MAX_PER_RULE = 500;

  private final ReminderRuleQueries queries;
  private final DefaultReminderRules rules;
  private final NotificationQueries queue;
  private final ReminderScope scope;
  private final Map<ReminderTrigger, ReminderSource> sources;

  /**
   * Creates the runner.
   *
   * @param queries the SQL layer
   * @param rules the rule service, for the enabled rules of a tenant
   * @param queue where a raised reminder goes — an ordinary queued notification, so it is
   *     delivered, retried, dead-lettered and logged like everything else (REQ-NOTI-005). Written
   *     through this block's own SQL rather than through {@code Notifications.raise}, because that
   *     one resolves the subscriptions of <b>one</b> person and a rule belongs to the tenant
   * @param scope used to narrow a rule by its saved search — a port, because a call to {@code
   *     SavedSearches} would close a three-hop module cycle
   * @param registered every source; two claiming one trigger is a configuration error and fails
   *     here rather than letting one of them win silently
   */
  public ReminderRunner(
      ReminderRuleQueries queries,
      DefaultReminderRules rules,
      NotificationQueries queue,
      ReminderScope scope,
      List<ReminderSource> registered) {
    this.queries = queries;
    this.rules = rules;
    this.queue = queue;
    this.scope = scope;
    this.sources =
        registered.stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    ReminderSource::trigger,
                    Function.identity(),
                    (one, other) -> {
                      throw new IllegalStateException(
                          "Two reminder sources claim the trigger " + one.trigger());
                    }));
  }

  /**
   * Runs every tenant's rules.
   *
   * @param today the day to judge against
   * @return how many notifications were raised
   */
  public int runAll(LocalDate today) {
    int raised = 0;
    for (UUID tenantId : queries.tenantsWithRules()) {
      // The context around the transaction and never inside it: `SET LOCAL
      // app.tenant_id` is applied when the transaction begins, from the context
      // current at that moment -- the shape `DeliveryDispatcher` uses.
      raised += TenantContext.callAs(tenantId, () -> runFor(tenantId, today));
    }
    return raised;
  }

  /**
   * Runs one tenant's rules, in that tenant's context.
   *
   * @param tenantId whose rules
   * @param today the day to judge against
   * @return how many notifications were raised
   */
  @Transactional
  public int runFor(UUID tenantId, LocalDate today) {
    int raised = 0;
    for (ReminderRuleView rule : rules.enabledOf(tenantId)) {
      ReminderSource source = sources.get(rule.trigger());
      if (source == null) {
        // A rule whose trigger stopped being served -- a plugin or a module gone.
        // Said once per pass and not silently skipped: a rule that quietly does
        // nothing is what this whole design is against.
        log.warn(
            "The reminder rule '{}' names {}, which nothing serves here; it raised nothing",
            rule.name(),
            rule.trigger());
        continue;
      }
      raised += runOne(tenantId, rule, source, today);
    }
    return raised;
  }

  private int runOne(
      UUID tenantId, ReminderRuleView rule, ReminderSource source, LocalDate today) {
    // THE OFFSET IS APPLIED TO THE QUESTION. "Warn me 14 days early" is "what is
    // due by today + 14", so the source answers about dates and never about
    // rules. A negative offset asks about the past, which is what an overdue
    // reminder is (REQ-LIFE-006).
    LocalDate horizon = rule.trigger().isDated() ? today.plusDays(rule.offsetDays()) : today;
    List<ReminderSource.Due> due = source.dueBy(horizon, MAX_PER_RULE);
    if (due.isEmpty()) {
      return 0;
    }

    Set<UUID> narrowedTo = narrowing(rule);
    int raised = 0;
    for (ReminderSource.Due thing : due) {
      if (narrowedTo != null && !narrowedTo.contains(thing.itemId())) {
        continue;
      }
      // Recorded first and sent only if this call is the one that recorded it.
      // The other order would send and then fail to record, which repeats.
      if (!queries.recordRaised(
          tenantId, rule.id(), rule.trigger().subjectKind(), thing.subjectId(), thing.dueOn())) {
        continue;
      }
      // One message per person who asked for this kind on this channel. A rule is
      // the tenant's and has no single recipient, so REQ-NOTI-006 decides who
      // hears it -- and a rule nobody subscribed to raises nothing rather than
      // shouting at everybody.
      String kind = kindOf(rule.trigger());
      for (ReminderRuleQueries.Subscriber who :
          queries.subscribersOf(tenantId, kind, rule.channelKey())) {
        queue.queue(
            tenantId,
            who.userId(),
            kind,
            rule.channelKey(),
            who.address(),
            subjectLine(rule, thing),
            bodyOf(rule, thing),
            null,
            who.language(),
            // One key per rule, thing, date AND person: the same news to two
            // people is two messages, and a shared key would make the second
            // look like a repeat of the first.
            rule.id() + ":" + thing.subjectId() + ":" + thing.dueOn() + ":" + who.userId());
        raised++;
      }
    }
    return raised;
  }

  /**
   * The ids the rule's saved search matches, or {@code null} when it narrows nothing.
   *
   * <p>{@code null} rather than "everything" deliberately: an empty set and "no narrowing" are
   * different, and confusing them would make a rule whose search matches nothing remind about
   * everything.
   *
   * @param rule the rule
   * @return the ids to keep, or {@code null} for no narrowing
   */
  private Set<UUID> narrowing(ReminderRuleView rule) {
    if (rule.savedSearchId() == null) {
      return null;
    }
    if (!scope.exists(rule.savedSearchId())) {
      // The search was removed and the foreign key set the column to null, but
      // this view may predate that. Widening is the documented behaviour of that
      // `ON DELETE SET NULL`.
      return null;
    }
    return scope.idsMatching(rule.savedSearchId(), 200);
  }

  /**
   * The notification kind, which is what a person subscribes to (REQ-NOTI-006).
   *
   * <p>One kind per trigger rather than one for "reminder": somebody who wants to hear about an
   * overdue loan does not necessarily want to hear about every warranty.
   *
   * @param trigger the rule's trigger
   * @return the kind
   */
  private static String kindOf(ReminderTrigger trigger) {
    return "reminder." + trigger.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
  }

  private static String subjectLine(ReminderRuleView rule, ReminderSource.Due thing) {
    return rule.name() + ": " + thing.label();
  }

  private static String bodyOf(ReminderRuleView rule, ReminderSource.Due thing) {
    return thing.label() + " — " + rule.name() + " (" + thing.dueOn() + ")";
  }
}
