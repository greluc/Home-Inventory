/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import de.greluc.homeinv.authorization.api.Permission;
import de.greluc.homeinv.authorization.api.RequiresPermission;
import de.greluc.homeinv.identity.api.AuthenticatedUser;
import de.greluc.homeinv.notification.api.Notifications;
import de.greluc.homeinv.notification.api.WebhookTargets;
import de.greluc.homeinv.platform.EventType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
 * Webhook targets and their delivery log (REQ-API-010, 08 §8.1).
 *
 * <p>An adapter and nothing else: it decides nothing, which is what ADR-0010 requires of every
 * surface. Two things about it are worth noticing.
 *
 * <p><b>The signing secret goes in and never comes out.</b> {@link WebhookTargets.WebhookTargetView}
 * has no field for it, so no response carries it — not the create, not the read, not the list. A
 * tenant that has lost the secret it agreed with its receiver sets a new one on both sides; there is
 * no endpoint that reveals the stored one, deliberately.
 *
 * <p><b>Nothing here posts anything anywhere.</b> The core makes no outbound call at all
 * (ADR-0026); a matching change queues a delivery, and {@code plugin-webhook} signs it and sends it
 * from its own network segment through the egress proxy's allowlist. A target whose host the
 * operator has not allowed is accepted here and refused there, visibly, in the delivery log
 * (REQ-PLG-013).
 */
@RestController
@RequestMapping("/api/v1/webhooks")
@RequiredArgsConstructor
public class WebhookController {

  private final WebhookTargets targets;

  /**
   * The tenant's webhook targets.
   *
   * <p>Bounded rather than paged, like the reminder rules: a tenant has a handful of integrations,
   * and a cursor over a list that short is machinery nobody needs. The cap is not optional even so,
   * because REQ-NFR-010 applies to every collection without exception.
   *
   * @param limit how many at most; capped at 200
   * @return the targets, oldest first, never with a secret
   */
  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.WEBHOOK_READ)
  public List<WebhookTargets.WebhookTargetView> listTargets(
      @RequestParam(required = false, defaultValue = "50") @Positive @Max(200) int limit) {
    return targets.list().stream().limit(limit).toList();
  }

  /**
   * Which events this deployment raises (REQ-API-010).
   *
   * <p>Its own endpoint so that a form offers a subscription that works, exactly as {@code
   * /reminder-rules/triggers} does. The list is the one in {@code
   * docs/reference/event-types.yaml}, and a name absent from it is refused on save.
   *
   * @param limit how many at most; capped at 200, which this list cannot approach — the cap is
   *     here because REQ-NFR-010 admits no exception, and an enforced rule with exceptions is not
   *     enforced
   * @return every event type a target may subscribe to
   */
  @GetMapping(path = "/event-types", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.WEBHOOK_READ)
  public List<String> eventTypes(
      @RequestParam(required = false, defaultValue = "50") @Positive @Max(200) int limit) {
    return java.util.Arrays.stream(EventType.values())
        .map(EventType::id)
        .sorted()
        .limit(limit)
        .toList();
  }

  /**
   * One target.
   *
   * @param id which one
   * @return it
   */
  @GetMapping(path = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.WEBHOOK_READ)
  @CanFail(ProblemType.NOT_FOUND)
  public WebhookTargets.WebhookTargetView target(@PathVariable UUID id) {
    return targets.get(id);
  }

  /**
   * What has been delivered to one target (REQ-NOTI-005).
   *
   * <p>The same delivery log a person's notifications are in, filtered to this target: state,
   * attempts and when the next one is due. What each attempt said is in the notification's own
   * attempt list.
   *
   * @param id which target
   * @param limit how many at most; capped at 200
   * @return the deliveries, newest first
   */
  @GetMapping(path = "/{id}/deliveries", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.WEBHOOK_READ)
  @CanFail(ProblemType.NOT_FOUND)
  public List<Notifications.QueuedNotification> deliveries(
      @PathVariable UUID id,
      @RequestParam(required = false, defaultValue = "50") @Positive @Max(200) int limit) {
    return targets.deliveries(id, limit);
  }

  /**
   * Adds a target.
   *
   * @param request where, what for, and the secret to sign with
   * @param user the authenticated caller
   * @return the stored target, without its secret
   */
  @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.WEBHOOK_WRITE)
  @CanFail({ProblemType.VALIDATION_FAILED, ProblemType.RESOURCE_EXISTS})
  @ResponseStatus(HttpStatus.CREATED)
  public WebhookTargets.WebhookTargetView createTarget(
      @Valid @RequestBody TargetRequest request, @AuthenticationPrincipal AuthenticatedUser user) {
    return targets.create(request.toCommand(), user.userId());
  }

  /**
   * Replaces a target.
   *
   * @param id which one
   * @param request what it should now say; an absent secret keeps the stored one
   * @param http the request, for the {@code If-Match} header
   * @param user the authenticated caller
   * @return the stored target
   */
  @PutMapping(path = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.WEBHOOK_WRITE)
  @CanFail({
    ProblemType.NOT_FOUND,
    ProblemType.VALIDATION_FAILED,
    ProblemType.RESOURCE_EXISTS,
    ProblemType.PRECONDITION_REQUIRED,
    ProblemType.PRECONDITION_FAILED
  })
  public WebhookTargets.WebhookTargetView replaceTarget(
      @PathVariable UUID id,
      @Valid @RequestBody TargetRequest request,
      HttpServletRequest http,
      @AuthenticationPrincipal AuthenticatedUser user) {
    return targets.update(id, request.toCommand(), EntityTags.required(http), user.userId());
  }

  /**
   * Removes a target and everything delivered to it.
   *
   * @param id which one
   * @param user the authenticated caller
   */
  @DeleteMapping("/{id}")
  @RequiresPermission(Permission.WEBHOOK_WRITE)
  @CanFail(ProblemType.NOT_FOUND)
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void removeTarget(
      @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser user) {
    targets.remove(id, user.userId());
  }

  /**
   * The body of a webhook target (REQ-API-010).
   *
   * @param url where to post. {@code https} only, and checked three times over — here, by the
   *     column, and by the plugin including the address it resolves to (REQ-SEC-034)
   * @param description what a person calls it, or absent
   * @param eventTypes which events it wants, by the names {@code GET /event-types} lists. At least
   *     one: a target that subscribes to nothing would never be delivered to
   * @param signingSecret what to sign with, at least 32 characters. Required when creating; on a
   *     replace, absent keeps the stored one — a form that cannot show a secret must not clear it
   *     by being saved
   * @param enabled whether it receives anything; absent means it does
   */
  public record TargetRequest(
      @NotBlank @Size(max = 500) String url,
      @Size(max = 300) String description,
      @NotEmpty @Size(max = 50) Set<String> eventTypes,
      @Size(max = 200) String signingSecret,
      Boolean enabled) {

    /**
     * The command the port takes.
     *
     * @return the command, with the event names resolved and the optional field given its default
     * @throws IllegalArgumentException when a name is not one this deployment raises — answered
     *     {@code 400} with {@code validation-failed}, naming the one that is wrong, because a
     *     subscription to an event nobody raises would be a target that silently never fires
     */
    WebhookTargets.NewWebhookTarget toCommand() {
      Set<EventType> resolved = new LinkedHashSet<>();
      for (String name : eventTypes) {
        resolved.add(
            EventType.of(name)
                .orElseThrow(
                    () ->
                        new IllegalArgumentException(
                            "This deployment raises no event called '"
                                + name
                                + "'. GET /api/v1/webhooks/event-types lists the ones it does.")));
      }
      return new WebhookTargets.NewWebhookTarget(
          url, description, resolved, signingSecret, enabled == null || enabled);
    }
  }
}
