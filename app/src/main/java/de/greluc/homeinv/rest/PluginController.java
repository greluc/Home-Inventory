/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.rest;

import de.greluc.homeinv.authorization.api.Permission;
import de.greluc.homeinv.authorization.api.RequiresPermission;
import de.greluc.homeinv.identity.api.AuthenticatedUser;
import de.greluc.homeinv.plugins.api.PluginRegistry;
import de.greluc.homeinv.plugins.api.PluginSettings;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * What is installed, and what this tenant lets it do (REQ-PLG-005, REQ-PLG-006, 09 §9.4).
 *
 * <p>Reading is a member's: what foreign code may reach here is something anybody working in the
 * tenant has an interest in knowing. Agreeing to it is an administrator's, because it is the one
 * act that lets foreign code touch this tenant's data at all.
 *
 * <p>There is no endpoint that installs anything. Installation is an operator's act outside the
 * running system (REQ-PLG-013), and an API that could install would make that sentence false.
 */
@RestController
@RequestMapping("/api/v1/plugins")
@RequiredArgsConstructor
public class PluginController {

  private final PluginRegistry plugins;
  private final PluginSettings settings;

  /**
   * The plugins the operator installed, with what this tenant has permitted each.
   *
   * <p>Bounded at 200 like every list (REQ-NFR-010) and without a cursor, for the reason
   * {@link PluginRegistry#installed(int)} gives: the collection grows with the deployment rather
   * than with a tenant's data, and an operator runs a handful of plugin containers, not a page of
   * them. The parameter is here so that a client asking for fewer gets fewer, and so that the bound
   * is part of the contract rather than only of the query.
   *
   * @param limit how many at most; capped at 200
   * @return what is installed
   */
  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.PLUGIN_READ)
  @CanFail(ProblemType.MALFORMED_REQUEST)
  public List<PluginView> installedPlugins(
      @RequestParam(required = false, defaultValue = "200") @Positive @Max(200) int limit) {
    return plugins.installed(limit).stream().map(this::viewOf).toList();
  }

  /**
   * One plugin.
   *
   * @param pluginId its reverse-domain id
   * @return it, with what this tenant has permitted it
   */
  @GetMapping(path = "/{pluginId}", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.PLUGIN_READ)
  @CanFail(ProblemType.NOT_FOUND)
  public PluginView plugin(@PathVariable @Size(max = 200) String pluginId) {
    return viewOf(plugins.registration(pluginId));
  }

  /**
   * Agrees to one capability, for this tenant.
   *
   * <p>Idempotent: agreeing twice is one grant. A capability the plugin's manifest does not declare
   * is refused rather than recorded — consent to something it never asked for is consent to
   * nothing, and would be waiting as a permission if it asked later.
   *
   * @param pluginId the plugin
   * @param capability the capability, spelled as 09 §9.4 spells it
   * @param user the administrator agreeing
   */
  // `grantCapability` and `withdrawCapability` rather than `grant` and `revoke`:
  // a method name is an `operationId`, springdoc numbers colliding ones in
  // registration order, and a second `revoke` renamed three unrelated endpoints
  // in the generated clients. `revokeInvitation` is spelled out for this reason
  // too.
  @PutMapping(path = "/{pluginId}/capabilities/{capability}")
  @RequiresPermission(Permission.PLUGIN_CONSENT)
  @CanFail({ProblemType.NOT_FOUND, ProblemType.VALIDATION_FAILED})
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void grantCapability(
      @PathVariable @Size(max = 200) String pluginId,
      @PathVariable @Size(max = 100) String capability,
      @AuthenticationPrincipal AuthenticatedUser user) {
    plugins.grant(pluginId, capability, user.userId());
  }

  /**
   * Withdraws one capability, for this tenant.
   *
   * <p>Withdrawing what was never granted is not an error: what a caller wants is "this plugin may
   * not do this here", and that is already true.
   *
   * @param pluginId the plugin
   * @param capability the capability
   * @param user the administrator withdrawing it
   */
  @DeleteMapping(path = "/{pluginId}/capabilities/{capability}")
  @RequiresPermission(Permission.PLUGIN_CONSENT)
  @CanFail(ProblemType.NOT_FOUND)
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void withdrawCapability(
      @PathVariable @Size(max = 200) String pluginId,
      @PathVariable @Size(max = 100) String capability,
      @AuthenticationPrincipal AuthenticatedUser user) {
    plugins.revoke(pluginId, capability, user.userId());
  }


  /**
   * What this tenant configured for one plugin, and what it could configure.
   *
   * <p>Every setting the manifest declares, whether or not a value has been stored, because "what
   * it wants" without "what it has" is not something anybody can act on — the same reasoning the
   * capability list follows.
   *
   * <p><b>A secret's value never comes back.</b> {@code value} is null for one and {@code set} says
   * whether there is one to replace, which is what a password field does everywhere else.
   *
   * @param pluginId the plugin
   * @param limit how many at most; capped at 200 (REQ-NFR-010)
   * @return the settings, in the manifest's order
   */
  @GetMapping(path = "/{pluginId}/settings", produces = MediaType.APPLICATION_JSON_VALUE)
  @RequiresPermission(Permission.PLUGIN_READ)
  @CanFail({ProblemType.NOT_FOUND, ProblemType.MALFORMED_REQUEST})
  public List<SettingView> pluginSettings(
      @PathVariable @Size(max = 200) String pluginId,
      @RequestParam(required = false, defaultValue = "200") @Positive @Max(200) int limit) {
    return settings.configured(pluginId).stream()
        .limit(limit)
        .map(
            setting ->
                new SettingView(
                    setting.key(),
                    setting.type(),
                    setting.required(),
                    setting.values(),
                    setting.defaultValue(),
                    setting.label(),
                    setting.value(),
                    setting.set()))
        .toList();
  }

  /**
   * Configures one setting, for this tenant.
   *
   * <p>A key the plugin's current manifest does not declare is refused rather than stored: it would
   * sit waiting to become live the day an update declared it, which is the same mistake as
   * consenting to a capability nobody asked for (REQ-PLG-006).
   *
   * @param pluginId the plugin
   * @param key the setting the manifest declares
   * @param body the value
   * @param user the administrator configuring it
   */
  // No `consumes`: a media type on the mapping is matched BEFORE the access
  // decision, so a caller without the permission would be told 415 rather than
  // 403 -- which leaks that the endpoint exists and fails REQ-SEC-026's check.
  // `MediaController` and the import upload learned this the same way.
  @PutMapping(path = "/{pluginId}/settings/{key}")
  @RequiresPermission(Permission.PLUGIN_CONFIGURE)
  @CanFail({ProblemType.NOT_FOUND, ProblemType.VALIDATION_FAILED})
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void configurePlugin(
      @PathVariable @Size(max = 200) String pluginId,
      @PathVariable @Size(max = 100) String key,
      @RequestBody @Valid SettingRequest body,
      @AuthenticationPrincipal AuthenticatedUser user) {
    settings.set(pluginId, key, body.value(), user.userId());
  }

  /**
   * Removes one setting, so the manifest's default applies again.
   *
   * <p>Removing what was never set is not an error, for the reason withdrawing a capability is not.
   *
   * @param pluginId the plugin
   * @param key the setting
   * @param user the administrator removing it
   */
  @DeleteMapping(path = "/{pluginId}/settings/{key}")
  @RequiresPermission(Permission.PLUGIN_CONFIGURE)
  @CanFail(ProblemType.NOT_FOUND)
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void clearPluginSetting(
      @PathVariable @Size(max = 200) String pluginId,
      @PathVariable @Size(max = 100) String key,
      @AuthenticationPrincipal AuthenticatedUser user) {
    settings.clear(pluginId, key, user.userId());
  }

  /**
   * Adds this tenant's answer to an installed plugin.
   *
   * @param registration what the operator installed
   * @return the same, with what this tenant granted and what is still being asked
   */
  private PluginView viewOf(PluginRegistry.Registration registration) {
    Set<String> granted =
        plugins.grants(registration.pluginId()).stream()
            .map(PluginRegistry.Grant::capability)
            .collect(java.util.stream.Collectors.toSet());
    return new PluginView(
        registration.pluginId(),
        registration.name(),
        registration.version(),
        registration.vendor(),
        registration.contract(),
        registration.runtime(),
        registration.signed(),
        registration.disabled(),
        registration.registeredAt(),
        registration.capabilities().stream()
            .map(capability -> new CapabilityView(capability, granted.contains(capability)))
            .toList());
  }

  /**
   * One installed plugin, as this tenant sees it.
   *
   * @param id the reverse-domain id
   * @param name what a person sees
   * @param version the plugin's own version
   * @param vendor who publishes it
   * @param contract the contract range it supports
   * @param runtime {@code out-of-process} or {@code in-process}
   * @param signed whether the operator verified its signature. An unsigned plugin runs only where
   *     they allowed it, and a client shows that permanently (REQ-PLG-004)
   * @param disabled whether it is out of service
   * @param registeredAt when it was first seen
   * @param capabilities everything its manifest asks for, each with whether this tenant agreed.
   *     Both halves together, because "what it wants" without "what it has" is not a decision
   *     anybody can take
   */
  public record PluginView(
      String id,
      String name,
      String version,
      String vendor,
      String contract,
      String runtime,
      boolean signed,
      boolean disabled,
      Instant registeredAt,
      List<CapabilityView> capabilities) {}

  /**
   * One setting a plugin declares, with what this tenant made of it.
   *
   * @param key the manifest's key
   * @param type {@code string}, {@code secret}, {@code enum}, {@code integer} or {@code boolean}
   * @param required whether the plugin says it cannot work without one
   * @param values the choices, for an {@code enum}; empty otherwise
   * @param defaultValue what applies when nothing is set here, or null
   * @param label what a person sees, by language tag — multilingual data, like a field label
   * @param value what this tenant set, and <b>always null for a secret</b>
   * @param set whether a value is stored, which is all a surface learns about a secret
   */
  public record SettingView(
      String key,
      String type,
      boolean required,
      List<String> values,
      String defaultValue,
      java.util.Map<String, String> label,
      String value,
      boolean set) {}

  /**
   * The value to store.
   *
   * @param value the value as text, of the type the manifest declares
   */
  public record SettingRequest(@NotBlank @Size(max = 4096) String value) {}

  /**
   * One capability a plugin asks for.
   *
   * @param id the capability, from the closed set of 09 §9.4
   * @param granted whether this tenant has agreed to it. False is the state every capability starts
   *     in: a plugin has no permissions until they are granted
   */
  public record CapabilityView(String id, boolean granted) {}
}
