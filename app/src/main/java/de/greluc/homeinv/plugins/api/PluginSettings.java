/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.plugins.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What a tenant configured for a plugin (ADR-0073, REQ-PLG-017).
 *
 * <h2>Two kinds of configuration, and only one of them is here</h2>
 *
 * <p>What the <b>operator</b> decides for the whole installation — the SMTP host, the S3 endpoint
 * and its keys, the OIDC client secret — is the plugin container's own environment, mounted into it
 * from {@code deploy/services.yaml} like every other service's credentials. It never passes through
 * the core, which therefore never holds it. A plugin is a container an operator declares
 * (REQ-PLG-013), so it is configured like one.
 *
 * <p>What a <b>tenant</b> decides is here: the signing secret agreed with its own webhook receiver,
 * the metadata source it prefers, its own token for a service it has an account with. The manifest's
 * {@code settings} block is exactly this list and nothing else — <b>a manifest describes what a
 * tenant may set; a container describes what an operator must set.</b>
 *
 * <h2>How a plugin receives them</h2>
 *
 * <p>In the call envelope, on every call, filled by {@code PluginResilience} from {@link
 * #effective}. Not fetched by the plugin: a fetch would be a read on the host channel, and
 * ADR-0071's admission test — <i>takes what the caller already holds</i> — refuses exactly that.
 *
 * <h2>What is refused</h2>
 *
 * <p>A key the current manifest does not declare, a value outside a declared {@code enum}'s list,
 * and a value that is not of the declared type. Storing one would be configuring something the
 * plugin never asked for, which would sit waiting to become live the day an update declared it —
 * the same mistake as granting a capability nobody asked for (REQ-PLG-006).
 */
public interface PluginSettings {

  /**
   * What to send this plugin for this tenant, with every secret opened.
   *
   * <p>Everything the manifest declares: a value the tenant set, otherwise the manifest's default,
   * and nothing at all for a setting with neither. A plugin therefore sees one map and does not
   * have to know which entries came from where.
   *
   * @param pluginId the plugin
   * @param tenantId the tenant, which must be the one the ambient context is set to — the sealed
   *     values are opened with that tenant's data key
   * @return the settings, never {@code null} and empty when nothing is declared or configured
   */
  Map<String, String> effective(String pluginId, UUID tenantId);

  /**
   * Whether this tenant has agreed that the core may send this plugin configuration at all.
   *
   * <p>{@link #effective} already refuses without the grant, and answers the same empty map when a
   * plugin simply declares no setting — which is the right answer for a caller assembling a map and
   * the wrong one for a caller asking whether it <b>may</b> send something the manifest does not
   * declare.
   *
   * <p>That caller exists since ADR-0077: a value can belong to the thing being acted on rather
   * than to the tenant — a webhook target's signing secret is per target, because one secret for a
   * tenant lets every receiver it configured forge a delivery to every other one. It is still the
   * tenant's configuration leaving the core, so it is still this capability that decides.
   *
   * @param pluginId the plugin
   * @param tenantId the tenant whose grant decides
   * @return whether {@code core:setting:read} is granted here
   */
  boolean maySendSettings(String pluginId, UUID tenantId);

  /**
   * What this tenant has configured, for a surface to show.
   *
   * <p>A secret's value is <b>never</b> returned: {@link Setting#value()} is {@code null} for one,
   * and {@link Setting#set()} says whether there is one to replace. An administration screen shows
   * "configured" and offers to overwrite, which is what a password field does everywhere else.
   *
   * @param pluginId the plugin
   * @return one entry per setting the manifest declares, in the manifest's order
   */
  List<Setting> configured(String pluginId);

  /**
   * Stores one value for this tenant.
   *
   * @param pluginId the plugin
   * @param key the setting, which the plugin's current manifest must declare
   * @param value the value as text, of the declared type
   * @param actor the administrator configuring it
   * @throws de.greluc.homeinv.platform.NotFoundException when nothing is installed under that id
   * @throws IllegalArgumentException when the manifest declares no such setting, or the value does
   *     not fit the declared type or the declared list of values
   */
  void set(String pluginId, String key, String value, UUID actor);

  /**
   * Removes one value, so the manifest's default applies again.
   *
   * <p>Removing something that was never set is not an error: the outcome a caller wants is "this
   * tenant configures nothing here", and that is already true.
   *
   * @param pluginId the plugin
   * @param key the setting
   * @param actor the administrator removing it
   */
  void clear(String pluginId, String key, UUID actor);

  /**
   * One setting a manifest declares, with what this tenant made of it.
   *
   * @param key the manifest's key
   * @param type {@code string}, {@code secret}, {@code enum}, {@code integer} or {@code boolean}
   * @param required whether the plugin says it cannot work without one
   * @param values the choices, for an {@code enum}; empty otherwise
   * @param defaultValue what applies when the tenant sets nothing, or {@code null}
   * @param label what a person sees, by language tag
   * @param value what this tenant set, or {@code null} — and <b>always {@code null} for a secret</b>,
   *     whether one is stored or not
   * @param set whether this tenant has stored a value, which is the only thing a surface learns
   *     about a secret
   */
  record Setting(
      String key,
      String type,
      boolean required,
      List<String> values,
      String defaultValue,
      Map<String, String> label,
      String value,
      boolean set) {}
}
