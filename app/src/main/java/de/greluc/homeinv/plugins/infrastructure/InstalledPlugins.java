/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.plugins.infrastructure;

import de.greluc.homeinv.plugin.api.ContractVersion;
import de.greluc.homeinv.plugin.api.InvalidManifestException;
import de.greluc.homeinv.plugin.api.PluginManifest;
import de.greluc.homeinv.plugin.api.PluginManifestReader;
import de.greluc.homeinv.plugins.api.PluginRegistry;
import de.greluc.homeinv.plugins.application.DefaultPluginRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Registers what the operator installed, at startup (REQ-PLG-008, REQ-PLG-013).
 *
 * <h2>Installation is an operator's act, and stays outside</h2>
 *
 * <p>There is no installation from inside the running system out of the internet (REQ-PLG-013). A
 * plugin is a container like any other service: the operator writes it into
 * {@code deploy/services.yaml}, and the generator produces this list beside the Quadlet units, the
 * plugin's own network segment (ADR-0037) and the egress allowlist its manifest declares. The core
 * reads the list and nothing else — it fetches nothing and it installs nothing.
 *
 * <p>Unset, which is what {@code minimal} leaves it, means no plugins. That is a complete
 * deployment and not a degraded one.
 *
 * <h2>A plugin can never stop the core from starting</h2>
 *
 * <p>Every failure here is logged and skipped: a manifest that cannot be read, a contract range
 * that does not cover this core, a file that is not there. 09 §9.11 puts it plainly — <i>a foreign
 * plugin must never prevent the system from starting</i> — and this is the one place where foreign
 * input is read before anything else works, so it is the one place that has to hold.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InstalledPlugins {

  /** How large the list may be. Beyond this it is not a list of plugins. */
  private static final int MAX_BYTES = 64 * 1024;

  private final DefaultPluginRegistry registry;

  /**
   * Where the generated list of installed plugins is, or empty when there are none.
   *
   * <p>Generated from {@code deploy/services.yaml}, so the topology and the registration cannot
   * disagree: the same entry produces the container, the network segment, the egress allowlist and
   * this line.
   */
  @Value("${homeinv.plugins.file:}")
  private String listFile;

  /**
   * Reads the list and registers what is in it.
   *
   * <p>On {@code ApplicationReadyEvent}: a plugin is not needed to serve a request and a core that
   * refused to finish starting because of one would be exactly what REQ-PLG-008 forbids.
   *
   * @param ready the startup event, unused beyond its timing
   */
  @EventListener(ApplicationReadyEvent.class)
  public void registerInstalled(ApplicationReadyEvent ready) {
    if (listFile == null || listFile.isBlank()) {
      log.info("No plugin list is configured; this instance runs without plugins.");
      return;
    }
    Path path = Path.of(listFile);
    if (!Files.isReadable(path)) {
      log.warn(
          "HOMEINV_PLUGINS_FILE names {}, which cannot be read. No plugin is registered; the core"
              + " runs without them.",
          path);
      return;
    }

    List<Entry> entries;
    try {
      entries = read(Files.readAllBytes(path));
    } catch (IOException | RuntimeException unreadable) {
      log.warn("The plugin list at {} could not be read; no plugin is registered", path, unreadable);
      return;
    }

    int registered = 0;
    for (Entry entry : entries) {
      if (register(entry)) {
        registered++;
      }
    }
    log.info("{} of {} installed plugins registered", registered, entries.size());
  }

  /**
   * Registers one entry, or says why it was skipped.
   *
   * @param entry one line of the list
   * @return whether it was registered
   */
  private boolean register(Entry entry) {
    byte[] manifest;
    try {
      manifest = Files.readAllBytes(Path.of(entry.manifest()));
    } catch (IOException unreadable) {
      log.warn(
          "The manifest of {} at {} could not be read; it is not registered",
          entry.id(),
          entry.manifest());
      return false;
    }

    PluginManifest parsed;
    try {
      parsed = PluginManifestReader.read(manifest);
    } catch (InvalidManifestException invalid) {
      log.warn("The manifest of {} is not one this core can act on: {}", entry.id(), invalid.getMessage());
      return false;
    }

    if (!parsed.metadata().id().equals(entry.id())) {
      // The list and the manifest disagree about which plugin this is. Refused
      // rather than reconciled: a grant is recorded against the id, so taking
      // the wrong one would attach somebody's consent to the wrong plugin.
      log.warn(
          "The list calls this plugin {} and its manifest calls it {}; it is not registered",
          entry.id(),
          parsed.metadata().id());
      return false;
    }

    try {
      if (!ContractVersion.covers(parsed.spec().contract())) {
        // Disabled and reported, and the core carries on (09 §9.11).
        log.warn(
            "Plugin {} supports contract {} and this core serves {}; it is not registered",
            entry.id(),
            parsed.spec().contract(),
            ContractVersion.SERVED);
        return false;
      }
    } catch (InvalidManifestException unreadableRange) {
      log.warn("Plugin {}: {}", entry.id(), unreadableRange.getMessage());
      return false;
    }

    try {
      PluginRegistry.Registration registration =
          registry.register(manifest, entry.endpoint(), entry.fingerprint(), entry.signed());
      log.info(
          "Plugin {} {} is installed and asks for {}",
          registration.pluginId(),
          registration.version(),
          registration.capabilities());
      return true;
    } catch (RuntimeException failed) {
      log.warn("Plugin {} could not be registered", entry.id(), failed);
      return false;
    }
  }

  /**
   * Reads the generated list.
   *
   * <p>{@link SafeConstructor}, for the same reason a manifest is read with one: this file is
   * generated here, but it names paths that are read next and a loader that can construct Java
   * types is a loader that does not need to be.
   *
   * @param yaml the list
   * @return its entries
   */
  static List<Entry> read(byte[] yaml) {
    LoaderOptions options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    options.setCodePointLimit(MAX_BYTES);
    Object document =
        new Yaml(new SafeConstructor(options)).load(new String(yaml, StandardCharsets.UTF_8));
    if (document == null) {
      return List.of();
    }
    if (!(document instanceof Map<?, ?> root)) {
      throw new IllegalArgumentException("The plugin list is a mapping with a `plugins` key");
    }
    Object plugins = root.get("plugins");
    if (plugins == null) {
      return List.of();
    }
    if (!(plugins instanceof List<?> list)) {
      throw new IllegalArgumentException("`plugins` is a list");
    }

    List<Entry> entries = new ArrayList<>();
    for (Object element : list) {
      if (!(element instanceof Map<?, ?> entry)) {
        throw new IllegalArgumentException("Every entry of `plugins` is a mapping");
      }
      entries.add(
          new Entry(
              required(entry, "id"),
              required(entry, "manifest"),
              optional(entry, "endpoint"),
              optional(entry, "fingerprint"),
              Boolean.TRUE.equals(entry.get("signed"))));
    }
    return List.copyOf(entries);
  }

  private static String required(Map<?, ?> entry, String key) {
    Object value = entry.get(key);
    if (!(value instanceof String text) || text.isBlank()) {
      throw new IllegalArgumentException("A plugin entry has no " + key);
    }
    return text;
  }

  private static String optional(Map<?, ?> entry, String key) {
    Object value = entry.get(key);
    return value instanceof String text && !text.isBlank() ? text : null;
  }

  /**
   * One installed plugin, as the operator's list names it.
   *
   * @param id the reverse-domain id, which must match the manifest's
   * @param manifest where the manifest file is, inside this container
   * @param endpoint where the plugin listens, or {@code null} for an in-process one
   * @param fingerprint the certificate that may answer there, pinned like every in-deployment peer
   *     (REQ-SEC-056, ADR-0044)
   * @param signed whether the operator has verified the signature. Not verified here: {@code
   *     cosign} is the operator's tool and this is what they report having run (REQ-PLG-004)
   */
  record Entry(String id, String manifest, String endpoint, String fingerprint, boolean signed) {}
}
