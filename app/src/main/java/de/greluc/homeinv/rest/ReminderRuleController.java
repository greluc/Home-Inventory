/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import de.greluc.homeinv.authorization.api.Permission;
import de.greluc.homeinv.authorization.api.RequiresPermission;
import de.greluc.homeinv.identity.api.AuthenticatedUser;
import de.greluc.homeinv.notification.api.ReminderRules;
import de.greluc.homeinv.notification.api.ReminderTrigger;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reminder rules (REQ-NOTI-001).
 *
 * <p>An adapter and nothing else: it decides nothing, which is what ADR-0010 requires of every
 * surface. The one thing worth noticing here is what is <b>not</b> a parameter — the recipient. A
 * rule belongs to the tenant, and who hears about it is decided by what each person subscribed to
 * (REQ-NOTI-006), not by whoever wrote the rule.
 */
@RestController
@RequestMapping("/api/v1/reminder-rules")
@RequiredArgsConstructor
public class ReminderRuleController {

  private final ReminderRules rules;

  /**
   * The tenant's reminder rules.
   *
   * <p>Bounded rather than paged: a household has a handful of rules, and a cursor over a list that
   * short is machinery nobody needs — the argument {@code MaintenanceLog.entriesOf} makes. The cap
   * is not optional even so, because REQ-NFR-010 applies to <b>every</b> collection without
   * exception, and a rule with exceptions is not one a check can enforce.
   *
   * @param limit how many at most; capped at 200
   * @return the rules, oldest first
   */
  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.SEARCH_QUERY)
  public List<ReminderRules.ReminderRuleView> listRules(
      @RequestParam(required = false, defaultValue = "50") @Positive @Max(200) int limit) {
    return rules.list().stream().limit(limit).toList();
  }

  /**
   * Which triggers this installation can actually answer (REQ-NOTI-003).
   *
   * <p>Its own endpoint so that a client offers a choice that works. Two of the six triggers have
   * no source behind them — a stocktake is stage 2 and no licence expiry is stored — and a form
   * listing them would be offering a rule that is refused on save.
   *
   * @param limit how many at most; capped at 200, which this list cannot approach — the cap is
   *     here because REQ-NFR-010 admits no exception, and an enforced rule with exceptions is not
   *     enforced
   * @return the triggers a rule may name here
   */
  @GetMapping(path = "/triggers", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.SEARCH_QUERY)
  public List<ReminderTrigger> servedTriggers(
      @RequestParam(required = false, defaultValue = "50") @Positive @Max(200) int limit) {
    return rules.servedTriggers().stream().sorted().limit(limit).toList();
  }

  /**
   * One rule.
   *
   * @param id which one
   * @return it
   */
  @GetMapping(path = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.SEARCH_QUERY)
  @CanFail(ProblemType.NOT_FOUND)
  public ReminderRules.ReminderRuleView rule(@PathVariable UUID id) {
    return rules.get(id);
  }

  /**
   * Creates a rule.
   *
   * @param request what to watch, which of them, when and where
   * @param user the authenticated caller
   * @return the stored rule
   */
  @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.REMINDER_RULE_WRITE)
  @CanFail({
    ProblemType.VALIDATION_FAILED,
    ProblemType.NOT_FOUND,
    ProblemType.UNSERVED_TRIGGER
  })
  @ResponseStatus(HttpStatus.CREATED)
  public ReminderRules.ReminderRuleView createRule(
      @Valid @RequestBody RuleRequest request, @AuthenticationPrincipal AuthenticatedUser user) {
    return rules.create(request.toCommand(), user.userId());
  }

  /**
   * Replaces a rule.
   *
   * @param id which one
   * @param request what it should now say
   * @param http the request, for the {@code If-Match} header
   * @param user the authenticated caller
   * @return the stored rule
   */
  @PutMapping(path = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.REMINDER_RULE_WRITE)
  @CanFail({
    ProblemType.NOT_FOUND,
    ProblemType.VALIDATION_FAILED,
    ProblemType.UNSERVED_TRIGGER,
    ProblemType.PRECONDITION_REQUIRED,
    ProblemType.PRECONDITION_FAILED
  })
  public ReminderRules.ReminderRuleView replaceRule(
      @PathVariable UUID id,
      @Valid @RequestBody RuleRequest request,
      HttpServletRequest http,
      @AuthenticationPrincipal AuthenticatedUser user) {
    return rules.update(id, request.toCommand(), EntityTags.required(http), user.userId());
  }

  /**
   * Removes a rule.
   *
   * @param id which one
   * @param user the authenticated caller
   */
  @DeleteMapping("/{id}")
  @RequiresPermission(Permission.REMINDER_RULE_DELETE)
  @CanFail(ProblemType.NOT_FOUND)
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void removeRule(
      @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser user) {
    rules.remove(id, user.userId());
  }

  /**
   * The body of a reminder rule (REQ-NOTI-001).
   *
   * @param name what a person calls it
   * @param trigger which date or condition it watches. Only a trigger this installation serves is
   *     accepted; {@code GET /triggers} says which those are
   * @param savedSearchId which things to watch, or absent for everything the trigger finds
   * @param offsetDays days relative to the trigger's date: <b>positive warns early</b> (14 is a
   *     fortnight before a warranty ends), <b>negative waits until after</b> (-1 fires the day
   *     after a loan was due), zero fires on the day. Stored as zero for a trigger that has no
   *     date, so the screen and the behaviour agree
   * @param channelKey which channel, as a plugin manifest names it
   * @param enabled whether it fires; absent means it does
   */
  public record RuleRequest(
      @NotBlank @Size(max = 200) String name,
      @NotNull ReminderTrigger trigger,
      UUID savedSearchId,
      @Min(-365) @Max(365) Integer offsetDays,
      @NotBlank @Size(max = 60) String channelKey,
      Boolean enabled) {

    /**
     * The command the port takes.
     *
     * @return the command, with the two optional fields given their defaults
     */
    ReminderRules.NewReminderRule toCommand() {
      return new ReminderRules.NewReminderRule(
          name,
          trigger,
          savedSearchId,
          offsetDays == null ? 0 : offsetDays,
          channelKey,
          enabled == null || enabled);
    }
  }
}
