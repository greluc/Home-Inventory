/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.notification.application;

import de.greluc.homeinv.notification.api.ReminderRules;
import de.greluc.homeinv.notification.api.ReminderScope;
import de.greluc.homeinv.notification.api.ReminderSource;
import de.greluc.homeinv.notification.api.ReminderTrigger;
import de.greluc.homeinv.notification.api.UnservedTriggerException;
import de.greluc.homeinv.notification.infrastructure.ReminderRuleQueries;
import de.greluc.homeinv.platform.NotFoundException;
import de.greluc.homeinv.platform.TenantContext;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reminder rules (REQ-NOTI-001).
 *
 * <p>Thin on purpose: a rule is data, and there is almost nothing to decide about writing one down.
 * The one judgement it makes is <b>refusing a trigger nothing serves</b>, and it makes it when the
 * rule is written rather than when it runs.
 */
@Slf4j
@Service
public class DefaultReminderRules implements ReminderRules {

  private final ReminderRuleQueries rules;
  private final ReminderScope scope;

  /**
   * The triggers something can actually answer.
   *
   * <p>Derived from the registered {@link ReminderSource} beans rather than listed here, so that a
   * trigger becomes writable the moment a block implements it and not when somebody remembers to
   * add it to a second list.
   */
  private final Set<ReminderTrigger> served;

  /**
   * Creates the service.
   *
   * @param rules the SQL layer
   * @param scope used to check that a named saved search is this tenant's before a rule points at
   *     it — the database would refuse it anyway through the composite key, but as a constraint
   *     violation rather than as the 404 REQ-SEC-025 asks for. A port rather than a call to {@code
   *     SavedSearches}, because that call closes a three-hop module cycle
   * @param sources every registered source, which is what decides the writable triggers
   */
  public DefaultReminderRules(
      ReminderRuleQueries rules, ReminderScope scope, List<ReminderSource> sources) {
    this.rules = rules;
    this.scope = scope;
    this.served = sources.stream().map(ReminderSource::trigger).collect(Collectors.toSet());
    log.info("Reminder triggers that can be answered here: {}", served);
  }

  @Override
  @Transactional(readOnly = true)
  public List<ReminderRuleView> list() {
    return rules.all(TenantContext.require());
  }

  @Override
  @Transactional(readOnly = true)
  public ReminderRuleView get(UUID id) {
    return rules
        .byId(TenantContext.require(), id)
        .orElseThrow(() -> new NotFoundException("reminder rule", id));
  }

  @Override
  @Transactional
  public ReminderRuleView create(NewReminderRule command, UUID actor) {
    UUID tenantId = TenantContext.require();
    check(command);
    UUID id =
        rules.insert(
            tenantId,
            command.name(),
            command.trigger(),
            command.savedSearchId(),
            effectiveOffset(command),
            command.channelKey(),
            command.enabled(),
            actor);
    log.info("A reminder rule was created for {}", command.trigger());
    return rules.byId(tenantId, id).orElseThrow();
  }

  @Override
  @Transactional
  public ReminderRuleView update(
      UUID id, NewReminderRule command, OptionalLong expectedVersion, UUID actor) {
    UUID tenantId = TenantContext.require();
    ReminderRuleView current =
        rules.byId(tenantId, id).orElseThrow(() -> new NotFoundException("reminder rule", id));
    de.greluc.homeinv.platform.Versions.requireCurrent(
        "reminder rule", id, expectedVersion, current.version());
    check(command);

    rules.update(
        tenantId,
        id,
        command.name(),
        command.trigger(),
        command.savedSearchId(),
        effectiveOffset(command),
        command.channelKey(),
        command.enabled(),
        actor);
    return rules.byId(tenantId, id).orElseThrow();
  }

  @Override
  @Transactional
  public void remove(UUID id, UUID actor) {
    UUID tenantId = TenantContext.require();
    if (rules.delete(tenantId, id) == 0) {
      throw new NotFoundException("reminder rule", id);
    }
    log.info("A reminder rule was removed by {}", actor);
  }

  /**
   * Refuses what cannot work, while the person can still change it.
   *
   * @param command the rule being written
   */
  private void check(NewReminderRule command) {
    if (!served.contains(command.trigger())) {
      // Refused here rather than accepted and then never firing. A rule that
      // looks saved and does nothing is the failure a reminder feature cannot
      // afford, and it would look like working software until somebody needed it.
      throw new UnservedTriggerException(
          "Nothing can answer the trigger "
              + command.trigger()
              + " in this installation yet. The triggers that work here are: "
              + served.stream().map(Enum::name).sorted().collect(Collectors.joining(", ")));
    }
    if (command.savedSearchId() != null && !scope.exists(command.savedSearchId())) {
      // Another tenant's search is the 404 an unknown one is (REQ-SEC-025),
      // rather than the foreign-key violation the database would raise.
      throw new NotFoundException("saved search", command.savedSearchId());
    }
  }

  /**
   * The offset as stored, which is zero for a trigger the offset means nothing for.
   *
   * <p>Normalised on the way in rather than ignored on the way out: a rule that displays "3 days
   * before" and behaves as "when it happens" is a rule whose screen lies about it.
   *
   * @param command the rule being written
   * @return the offset to store
   */
  private int effectiveOffset(NewReminderRule command) {
    return command.trigger().isDated() ? command.offsetDays() : 0;
  }

  /**
   * Which triggers this installation can answer.
   *
   * @return the served triggers, for the surface that offers a choice
   */
  public Set<ReminderTrigger> servedTriggers() {
    return Set.copyOf(served);
  }

  /**
   * Not part of the port: used by the run, which needs the enabled ones only.
   *
   * @param tenantId whose
   * @return the rules that fire
   */
  @Transactional(readOnly = true)
  public List<ReminderRuleView> enabledOf(UUID tenantId) {
    return rules.enabled(tenantId);
  }

}
