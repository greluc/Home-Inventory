/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.plugins.application;

import de.greluc.homeinv.crypto.api.SensitiveValues;
import de.greluc.homeinv.platform.NotFoundException;
import de.greluc.homeinv.platform.TenantContext;
import de.greluc.homeinv.plugin.api.PluginManifest;
import de.greluc.homeinv.plugin.api.PluginManifestReader;
import de.greluc.homeinv.plugins.api.PluginRegistry;
import de.greluc.homeinv.plugins.api.PluginSettings;
import de.greluc.homeinv.plugins.infrastructure.PluginRegistryQueries;
import de.greluc.homeinv.plugins.infrastructure.PluginSettingQueries;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a tenant configured for a plugin, validated against the manifest (ADR-0073, REQ-PLG-017).
 *
 * <h2>The manifest decides what may be stored</h2>
 *
 * <p>A key the current manifest does not declare is refused, and so is a value outside a declared
 * {@code enum}'s list or of the wrong type. That is the same rule the capability grants follow:
 * configuring something a plugin never asked for would sit in the table waiting to become live the
 * day an update declared it, which is the shape of a privilege that arrives without anybody
 * agreeing to it (REQ-PLG-006).
 *
 * <h2>A secret leaves in exactly two directions</h2>
 *
 * <p>Into the call envelope, opened, on a mutually authenticated connection to the plugin that owns
 * it — and nowhere else. It is never returned by {@link #configured}, never logged and never put in
 * a problem document: an administration surface shows that one is stored and offers to replace it,
 * which is what a password field does everywhere else.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultPluginSettings implements PluginSettings {

  /**
   * How long a value may be.
   *
   * <p>Generous for a token and far short of a file. A setting is configuration; a caller with more
   * than this has something that belongs in the plugin container's own environment (ADR-0073).
   */
  private static final int MAX_VALUE = 4096;

  /** The one type whose value is sealed at rest and never read back out to a person. */
  private static final String SECRET = "secret";

  /**
   * What a tenant grants before its values travel to a plugin (09 §9.4).
   *
   * <p>The capability existed before there was anything to read, and this is what it now means: not
   * "the plugin may fetch its settings" — nothing fetches, the core sends — but <b>the core may
   * send them</b>. Without the grant a plugin receives an empty map, which keeps REQ-PLG-005's
   * sentence true for the one kind of value a tenant is most likely to mind: a secret it holds with
   * somebody else.
   */
  private static final String CAPABILITY = "core:setting:read";

  private final PluginRegistry registrations;
  private final PluginRegistryQueries registry;
  private final PluginSettingQueries settings;
  private final SensitiveValues sealing;

  @Override
  @Transactional(readOnly = true)
  public Map<String, String> effective(String pluginId, UUID tenantId) {
    List<PluginManifest.Setting> declared = declaredOrNothing(pluginId);
    if (declared.isEmpty()) {
      return Map.of();
    }
    if (!registrations.permits(pluginId, CAPABILITY)) {
      // Configured and not consented to. Said once, at debug, because it is a
      // real diagnosis -- a plugin that behaves as if it were unconfigured
      // usually is -- and because it must not become a line per call.
      log.debug(
          "Plugin {} declares settings and this tenant has not granted {}, so it is sent none",
          pluginId,
          CAPABILITY);
      return Map.of();
    }

    Map<String, PluginSettingQueries.Stored> stored = storedByKey(pluginId);
    Map<String, String> resolved = new LinkedHashMap<>();
    for (PluginManifest.Setting setting : declared) {
      PluginSettingQueries.Stored value = stored.get(setting.key());
      if (value != null) {
        resolved.put(setting.key(), open(pluginId, setting.key(), value));
      } else if (setting.defaultValue() != null && !setting.defaultValue().isBlank()) {
        // The manifest's own default, so a plugin sees one map rather than
        // having to know which entries the tenant touched.
        resolved.put(setting.key(), setting.defaultValue());
      }
    }
    return Map.copyOf(resolved);
  }

  @Override
  public boolean maySendSettings(String pluginId, UUID tenantId) {
    // The tenant is the ambient one -- `registrations.permits` reads a grant
    // under row-level security -- and the parameter is here so that a caller
    // cannot ask about one tenant while acting for another without saying so.
    return TenantContext.current().filter(tenantId::equals).isPresent()
        && registrations.permits(pluginId, CAPABILITY);
  }

  @Override
  @Transactional(readOnly = true)
  public List<PluginSettings.Setting> configured(String pluginId) {
    // `declared` and not `declaredOrNothing`: this is a surface, and a plugin id
    // nothing is installed under must answer "not found" rather than an empty
    // list. An endpoint that answers 200 for an id that does not exist is the
    // shape REQ-SEC-025 forbids, and `EndpointNegativeCoverageIT` drives it.
    List<PluginManifest.Setting> settingsOfManifest = declared(pluginId);
    Map<String, PluginSettingQueries.Stored> stored = storedByKey(pluginId);
    List<PluginSettings.Setting> answer = new ArrayList<>();
    for (PluginManifest.Setting setting : settingsOfManifest) {
      PluginSettingQueries.Stored value = stored.get(setting.key());
      boolean secret = SECRET.equals(setting.type());
      answer.add(
          new PluginSettings.Setting(
              setting.key(),
              setting.type(),
              setting.required(),
              setting.values(),
              setting.defaultValue(),
              setting.label(),
              // A secret's value never comes back out here, stored or not.
              secret || value == null ? null : value.value(),
              value != null));
    }
    return List.copyOf(answer);
  }

  @Override
  @Transactional
  public void set(String pluginId, String key, String value, UUID actor) {
    PluginManifest.Setting declared =
        declared(pluginId).stream()
            .filter(setting -> setting.key().equals(key))
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "The manifest of "
                            + pluginId
                            + " declares no setting '"
                            + key
                            + "'. Configuring one it never asked for would wait in the table until"
                            + " an update declared it."));

    String checked = checked(declared, value);
    boolean secret = SECRET.equals(declared.type());
    settings.put(
        pluginId,
        key,
        secret ? sealing.seal(entityOf(pluginId), key, checked) : checked,
        secret,
        actor);
    // The value never appears here, secret or not: a plugin setting is a
    // tenant's business and a log line is the deployment's (REQ-SEC-066).
    log.info("Tenant configured {} of plugin {}", key, pluginId);
  }

  @Override
  @Transactional
  public void clear(String pluginId, String key, UUID actor) {
    // Asked first, and for the same reason `configured` asks: clearing a setting
    // of a plugin nobody installed is not "already true", it is a request about
    // something that does not exist.
    declared(pluginId);
    if (settings.remove(pluginId, key)) {
      log.info("Tenant cleared {} of plugin {}", key, pluginId);
    }
  }

  // -------------------------------------------------------------------------

  /**
   * The settings the plugin's current manifest declares.
   *
   * @param pluginId the plugin
   * @return them, in the manifest's order
   * @throws NotFoundException when nothing is installed under that id
   */
  private List<PluginManifest.Setting> declared(String pluginId) {
    String document =
        registry
            .manifest(pluginId)
            .orElseThrow(() -> new NotFoundException("Plugin", pluginId));
    return PluginManifestReader.read(document.getBytes(StandardCharsets.UTF_8)).spec().settings();
  }

  /**
   * The same, for the read paths, where an unreadable manifest is not worth failing a call over.
   *
   * @param pluginId the plugin
   * @return the declared settings, or none
   */
  private List<PluginManifest.Setting> declaredOrNothing(String pluginId) {
    Optional<String> document = registry.manifest(pluginId);
    if (document.isEmpty()) {
      return List.of();
    }
    try {
      return PluginManifestReader.read(document.get().getBytes(StandardCharsets.UTF_8))
          .spec()
          .settings();
    } catch (RuntimeException unreadable) {
      // The same answer the resolution gives an unreadable manifest: skip it and
      // say so once, rather than failing the call that happened to be first.
      log.warn(
          "The stored manifest of {} cannot be read, so it is sent no settings: {}",
          pluginId,
          unreadable.getMessage());
      return List.of();
    }
  }

  private Map<String, PluginSettingQueries.Stored> storedByKey(String pluginId) {
    Map<String, PluginSettingQueries.Stored> byKey = new LinkedHashMap<>();
    for (PluginSettingQueries.Stored stored : settings.of(pluginId)) {
      byKey.put(stored.key(), stored);
    }
    return byKey;
  }

  /**
   * Opens a stored value, or takes it as it is.
   *
   * <p>The row says whether it is sealed, and the <b>manifest is not asked</b>: an update that
   * stopped calling a setting a secret must not turn stored ciphertext into something a plugin
   * receives as a value.
   *
   * @param pluginId the plugin, bound into the ciphertext
   * @param key the setting, bound into the ciphertext
   * @param stored what the table holds
   * @return the value
   */
  private String open(String pluginId, String key, PluginSettingQueries.Stored stored) {
    return stored.sealed() ? sealing.open(entityOf(pluginId), key, stored.value()) : stored.value();
  }

  /**
   * The identity a setting's ciphertext is bound to.
   *
   * <p>Envelope encryption binds tenant, entity and field into the additional authenticated data
   * (ADR-0019), and a plugin id is text rather than a UUID — so the entity is derived from it,
   * deterministically. The consequence is the one that matters: a sealed value cannot be moved to
   * another plugin, another key or another tenant and still open.
   *
   * @param pluginId the plugin
   * @return the derived entity id
   */
  private static UUID entityOf(String pluginId) {
    return UUID.nameUUIDFromBytes(("plugin-setting:" + pluginId).getBytes(StandardCharsets.UTF_8));
  }

  /**
   * The value, checked against what the manifest declared.
   *
   * @param declared the setting
   * @param value what arrived
   * @return the value to store
   * @throws IllegalArgumentException when it does not fit
   */
  private static String checked(PluginManifest.Setting declared, String value) {
    if (value == null) {
      throw new IllegalArgumentException(
          "A setting is given a value or cleared; null is neither. Use the delete for 'no value'.");
    }
    if (value.length() > MAX_VALUE) {
      throw new IllegalArgumentException(
          "A setting value is at most " + MAX_VALUE + " characters (REQ-NFR-010)");
    }
    switch (declared.type()) {
      case "integer" -> {
        try {
          Long.parseLong(value.trim());
        } catch (NumberFormatException notANumber) {
          throw new IllegalArgumentException(
              "The setting '" + declared.key() + "' is an integer, and '" + value + "' is not one");
        }
      }
      case "boolean" -> {
        if (!"true".equals(value) && !"false".equals(value)) {
          throw new IllegalArgumentException(
              "The setting '" + declared.key() + "' is a boolean: 'true' or 'false', exactly");
        }
      }
      case "enum" -> {
        if (!declared.values().contains(value)) {
          throw new IllegalArgumentException(
              "The setting '"
                  + declared.key()
                  + "' takes one of "
                  + String.join(", ", declared.values())
                  + "; a value outside that list is one the plugin has no branch for");
        }
      }
      default -> {
        if (value.isBlank()) {
          throw new IllegalArgumentException(
              "The setting '" + declared.key() + "' was given a blank value. Clear it instead: a"
                  + " blank is a value, and 'no value' is what the manifest's default is for.");
        }
      }
    }
    return value;
  }

  /**
   * The tenant the ambient context is set to, for a caller that wants to be explicit.
   *
   * @return the tenant
   */
  static UUID currentTenant() {
    return TenantContext.require();
  }
}
