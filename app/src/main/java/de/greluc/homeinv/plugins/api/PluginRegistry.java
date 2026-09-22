/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.plugins.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What is installed, and what each tenant has permitted it (REQ-PLG-004…006, 09 §9.4).
 *
 * <h2>Two scopes, deliberately different</h2>
 *
 * <p>A <b>registration</b> is instance-wide: an operator installs a plugin the way they install any
 * other container, and there is no installation from inside the running system (REQ-PLG-013). A
 * <b>grant</b> is per tenant: an installed plugin is active for tenant A and invisible to tenant B
 * until B's administrator consents.
 *
 * <p>A plugin has <b>no</b> permissions at all until they are granted. There is no base
 * entitlement and no "read access, which is harmless anyway" — that sentence is 09 §9.4's, and
 * {@link #permits} is where it is true or not.
 *
 * <h2>What a new manifest does to consent</h2>
 *
 * <p>A manifest that asks for <i>more</i> than the one a tenant consented to does not take away
 * what was granted: the plugin carries on with the capabilities it has, and the new one is simply
 * not granted until somebody says yes (REQ-PLG-006, decided with the owner 2026-09-14). A plugin
 * that cannot work without the new capability fails at that call, visibly, which is better than an
 * update that silently disables a working plugin for every tenant — and far better than one that
 * silently escalates.
 */
public interface PluginRegistry {

  /**
   * What the operator has installed on this instance, by plugin id.
   *
   * <p>Bounded like every other collection (REQ-NFR-010), and without a cursor: this one grows with
   * the deployment rather than with a tenant's data, so a page size of 200 is a ceiling an operator
   * reaches by running two hundred plugin containers, not by using the system.
   *
   * @param limit how many at most
   * @return the registrations
   */
  List<Registration> installed(int limit);

  /**
   * One registration.
   *
   * @param pluginId the reverse-domain id
   * @return it
   * @throws de.greluc.homeinv.platform.NotFoundException when nothing is installed under that id
   */
  Registration registration(String pluginId);

  /**
   * What this tenant has granted one plugin.
   *
   * @param pluginId the plugin
   * @return the granted capabilities, in the order the manifest declares them
   */
  List<Grant> grants(String pluginId);

  /**
   * Whether this plugin may do this, here and now (REQ-PLG-005).
   *
   * <p>Asked before every plugin call. False when the plugin is not installed, when it is
   * disabled, when this tenant has not consented, and — importantly — when the capability is not in
   * the plugin's current manifest at all: a grant that outlived the capability it was for is not a
   * permission, it is a leftover.
   *
   * @param pluginId the plugin
   * @param capability the capability, from the closed set of 09 §9.4
   * @return whether the call may be made
   */
  boolean permits(String pluginId, String capability);

  /**
   * Grants one capability, in this tenant.
   *
   * @param pluginId the plugin
   * @param capability the capability, which the plugin's manifest must declare
   * @param actor the administrator consenting
   * @return the grant
   * @throws de.greluc.homeinv.platform.NotFoundException when nothing is installed under that id
   * @throws IllegalArgumentException when the manifest does not declare that capability — consent
   *     to something a plugin never asked for is consent to nothing, and would survive as a
   *     permission if the plugin later asked
   */
  Grant grant(String pluginId, String capability, UUID actor);

  /**
   * Withdraws one capability, in this tenant.
   *
   * <p>Withdrawing something that was never granted is not an error: the outcome a caller wants is
   * "this plugin may not do this here", and that is already true.
   *
   * @param pluginId the plugin
   * @param capability the capability
   * @param actor the administrator withdrawing it
   */
  void revoke(String pluginId, String capability, UUID actor);

  /**
   * What the <b>instance</b> has granted one plugin (ADR-0066).
   *
   * <p>A second level above the per-tenant grants, and a narrow one: it authorises only the calls
   * the deployment makes on its own behalf — today the security notifications of REQ-NOTI-004,
   * which must reach an account that belongs to no tenant. It is not a way around a tenant's
   * consent, because a call made under it carries no tenant context and therefore reaches no
   * tenant's data.
   *
   * @param pluginId the plugin
   * @return the instance-level grants, by capability
   */
  List<Grant> instanceGrants(String pluginId);

  /**
   * Whether this plugin may do this <b>for the instance</b> (ADR-0066).
   *
   * <p>The same four questions {@link #permits} asks, with the tenant's consent replaced by the
   * operator's: installed, not disabled, granted at instance level, and still declared by the
   * current manifest.
   *
   * @param pluginId the plugin
   * @param capability the capability, from the closed set of 09 §9.4
   * @return whether the instance-level call may be made
   */
  boolean permitsForInstance(String pluginId, String capability);

  /**
   * Grants one capability for the instance (ADR-0066).
   *
   * @param pluginId the plugin
   * @param capability the capability, which the plugin's manifest must declare
   * @param actor the instance operator consenting (ADR-0057)
   * @return the grant
   * @throws de.greluc.homeinv.platform.NotFoundException when nothing is installed under that id
   * @throws IllegalArgumentException when the manifest does not declare that capability
   */
  Grant grantForInstance(String pluginId, String capability, UUID actor);

  /**
   * Withdraws one instance-level capability (ADR-0066).
   *
   * <p>Withdrawing something never granted is not an error, for the reason {@link #revoke} gives.
   *
   * @param pluginId the plugin
   * @param capability the capability
   * @param actor the instance operator withdrawing it
   */
  void revokeForInstance(String pluginId, String capability, UUID actor);

  /**
   * One installed plugin.
   *
   * @param pluginId the reverse-domain id, which grants are recorded against
   * @param name what a person sees
   * @param version the plugin's own version
   * @param vendor who publishes it
   * @param contract the contract range it supports
   * @param runtime {@code out-of-process} or {@code in-process}
   * @param capabilities every capability its current manifest declares, whether granted or not
   * @param manifestDigest the SHA-256 of the manifest these capabilities came from
   * @param signed whether the manifest's signature verified. An unsigned plugin runs only where the
   *     operator allowed it, and an administration surface shows that permanently (REQ-PLG-004)
   * @param disabled whether the operator, a permanently open circuit or a failed signature took it
   *     out of service
   * @param registeredAt when it was first seen
   */
  record Registration(
      String pluginId,
      String name,
      String version,
      String vendor,
      String contract,
      String runtime,
      List<String> capabilities,
      String manifestDigest,
      boolean signed,
      boolean disabled,
      Instant registeredAt) {}

  /**
   * Takes a plugin out of service, or puts it back (REQ-SEC-082).
   *
   * <p>The immediate measure of 12 §12: one plugin stops being called at once, for every tenant,
   * without uninstalling it and without touching a grant. A disabled plugin answers nothing and
   * every call to it fails as though it were unavailable, which is the state a permanently open
   * circuit already puts it in — this is the same state, reached on purpose.
   *
   * <p>Enabling is the same call with {@code false}, and it restores exactly what was there: the
   * grants were never removed, so no tenant has to consent again to a plugin that was switched off
   * for an afternoon.
   *
   * @param pluginId which plugin
   * @param disabled true to take it out of service, false to put it back
   * @param actor the operator making the decision
   * @return true when a plugin was changed, false when nothing is installed under that id
   */
  boolean setDisabled(String pluginId, boolean disabled, UUID actor);

  /**
   * One capability a tenant has granted a plugin.
   *
   * @param pluginId the plugin
   * @param capability what it may do
   * @param manifestDigest which manifest the administrator was looking at when they agreed, so the
   *     surface can say what was consented to rather than what is asked for now
   * @param grantedAt when
   * @param grantedBy who
   */
  record Grant(
      String pluginId, String capability, String manifestDigest, Instant grantedAt, UUID grantedBy) {}
}
