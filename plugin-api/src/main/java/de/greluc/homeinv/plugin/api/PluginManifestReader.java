/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: Apache-2.0
 */
package de.greluc.homeinv.plugin.api;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Reads a manifest, and refuses one that cannot mean anything (REQ-PLG-004).
 *
 * <h2>Safely, because a manifest is a stranger's document</h2>
 *
 * <p>{@link SafeConstructor}, so the YAML can name no Java type: an unrestricted loader turns
 * {@code !!javax.script.ScriptEngineManager} into remote code execution, and a manifest arrives from
 * whoever wrote the plugin. There are no aliases either, which is what stops a document that expands
 * to gigabytes from being a denial of service by itself.
 *
 * <h2>Read, not mapped</h2>
 *
 * <p>Field by field rather than through a reflective binder. A manifest is small and the refusals
 * are the valuable part: "capability {@code network:outbound} names no host" is something an author
 * can act on, where a binder's message names a Java field nobody outside this repository has seen.
 * It also means an unknown key is <b>refused</b> rather than ignored — a typo in a capability id is
 * a permission that silently would not have been asked for.
 *
 * <p>Both sides use it: the core when it registers a plugin, the SDK when it checks one before
 * shipping. Two readers of one format is one reader too many.
 */
public final class PluginManifestReader {

  /** How large a manifest may be. Beyond this it is not a manifest. */
  private static final int MAX_BYTES = 256 * 1024;

  /** The capabilities of 09 §9.4, and nothing else. A typo is a refusal, not a silent omission. */
  private static final Set<String> CAPABILITIES =
      Set.of(
          "core:item:read",
          "core:item:write",
          "core:location:read",
          "core:location:write",
          "core:media:read",
          "core:media:write",
          "core:event:emit",
          "core:event:subscribe",
          "core:setting:read",
          "network:outbound",
          "ui:panel",
          "print:target");

  /**
   * The extension points of REQ-PLG-001. A port outside this list is a port nobody calls.
   *
   * <p>Package-private rather than private so that {@code PortCatalogueTest} can compare it with
   * the interfaces in {@code de.greluc.homeinv.plugin.api.port}. It had drifted: {@code
   * PasswordBreachCheck} was added as the fifteenth port by ADR-0067 and never added here, which
   * made every manifest declaring it invalid — a plugin nobody could install, failing at
   * registration with a message listing the ports it was not among. That is exactly the shape of
   * bug a list written twice produces, and the comparison is what stops the next one.
   */
  static final Set<String> PORTS =
      Set.of(
          "CodeFormat",
          "ScanSource",
          "LabelRenderer",
          "PrintTarget",
          "LabelMediaProvider",
          "MetadataResolver",
          "BlobStore",
          "SearchIndex",
          "NotificationChannel",
          "IdentityProvider",
          "ImageProcessor",
          "VirusScanner",
          "ValuationProvider",
          "ImportMapper",
          "PasswordBreachCheck",
          "DocumentRenderer");

  /** The setting types a manifest may declare. */
  private static final Set<String> SETTING_TYPES =
      Set.of("string", "secret", "enum", "integer", "boolean");

  private PluginManifestReader() {
    // A reader with no state.
  }

  /**
   * Reads one manifest.
   *
   * @param yaml the document, as the bytes that were signed
   * @return the manifest
   * @throws InvalidManifestException when it is not a manifest this core can act on, naming what is
   *     wrong in terms its author will recognise
   */
  public static PluginManifest read(byte[] yaml) {
    if (yaml == null || yaml.length == 0) {
      throw new InvalidManifestException("The manifest is empty");
    }
    if (yaml.length > MAX_BYTES) {
      throw new InvalidManifestException(
          "The manifest is larger than " + MAX_BYTES + " bytes, which no manifest is");
    }

    LoaderOptions options = new LoaderOptions();
    // No aliases: a small document that expands to gigabytes is a denial of
    // service the parser can refuse rather than survive.
    options.setAllowDuplicateKeys(false);
    options.setProcessComments(false);
    options.setCodePointLimit(MAX_BYTES);

    Object document;
    try {
      document = new Yaml(new SafeConstructor(options)).load(new String(yaml, StandardCharsets.UTF_8));
    } catch (RuntimeException notYaml) {
      throw new InvalidManifestException("The manifest is not valid YAML: " + notYaml.getMessage());
    }
    if (!(document instanceof Map<?, ?> root)) {
      throw new InvalidManifestException("A manifest is a mapping, not a " + kindOf(document));
    }

    String apiVersion = string(root, "apiVersion", true);
    if (!PluginManifest.API_VERSION.equals(apiVersion)) {
      throw new InvalidManifestException(
          "apiVersion is '"
              + apiVersion
              + "'; this core reads '"
              + PluginManifest.API_VERSION
              + "'. A manifest format is not guessed at.");
    }

    return new PluginManifest(
        apiVersion, metadata(mapping(root, "metadata", true)), spec(mapping(root, "spec", true)));
  }

  /**
   * Reads a manifest from a stream, closing nothing.
   *
   * @param in the document
   * @return the manifest
   * @throws InvalidManifestException when it cannot be read or is not a manifest
   */
  public static PluginManifest read(InputStream in) {
    try {
      return read(in.readAllBytes());
    } catch (java.io.IOException unreadable) {
      throw new InvalidManifestException("The manifest could not be read: " + unreadable.getMessage());
    }
  }

  private static PluginManifest.Metadata metadata(Map<?, ?> node) {
    reject(node, "metadata", Set.of("id", "name", "version", "vendor", "license", "homepage", "descriptions"));
    String id = string(node, "id", true);
    if (!id.matches("[a-z0-9]+(\\.[a-z0-9-]+)+")) {
      throw new InvalidManifestException(
          "metadata.id is '"
              + id
              + "'; a plugin id is a reverse-domain name in lower case, such as"
              + " de.greluc.homeinv.plugin.isbn. It is what a tenant's grant is recorded against,"
              + " so it has to be stable and unmistakable.");
    }
    return new PluginManifest.Metadata(
        id,
        string(node, "name", true),
        string(node, "version", true),
        string(node, "vendor", true),
        string(node, "license", true),
        string(node, "homepage", false),
        text(node, "descriptions"));
  }

  private static PluginManifest.Spec spec(Map<?, ?> node) {
    reject(
        node,
        "spec",
        Set.of("contract", "runtime", "implements", "capabilities", "settings", "health", "resources"));

    String runtime = string(node, "runtime", true);
    PluginManifest.Runtime parsed =
        switch (runtime) {
          case "out-of-process" -> PluginManifest.Runtime.OUT_OF_PROCESS;
          case "in-process" -> PluginManifest.Runtime.IN_PROCESS;
          default ->
              throw new InvalidManifestException(
                  "spec.runtime is '"
                      + runtime
                      + "'; it is 'out-of-process' or 'in-process'. The first is the default and the"
                      + " normal case (ADR-0006).");
        };

    List<PluginManifest.PortBinding> ports = new ArrayList<>();
    for (Map<?, ?> binding : sequence(node, "implements", true)) {
      reject(binding, "spec.implements[]", Set.of("port", "schemes", "priority"));
      String port = string(binding, "port", true);
      if (!PORTS.contains(port)) {
        throw new InvalidManifestException(
            "spec.implements names the port '"
                + port
                + "', which this core has no extension point for. The ports are: "
                + String.join(", ", PORTS.stream().sorted().toList()));
      }
      ports.add(new PluginManifest.PortBinding(port, strings(binding, "schemes"), integer(binding, "priority", 0)));
    }
    if (ports.isEmpty()) {
      throw new InvalidManifestException(
          "spec.implements names no port, so there is nothing this plugin could be called for");
    }

    List<PluginManifest.Capability> capabilities = new ArrayList<>();
    for (Map<?, ?> capability : sequence(node, "capabilities", false)) {
      reject(capability, "spec.capabilities[]", Set.of("id", "reason", "hosts", "tcp", "events"));
      String id = string(capability, "id", true);
      if (!CAPABILITIES.contains(id)) {
        throw new InvalidManifestException(
            "spec.capabilities names '"
                + id
                + "', which is not a capability this core grants. A typo here is a permission that"
                + " would silently never have been asked for, so it is refused rather than ignored.");
      }
      List<String> hosts = strings(capability, "hosts");
      List<String> tcp = strings(capability, "tcp");
      if ("network:outbound".equals(id) && hosts.isEmpty() && tcp.isEmpty()) {
        throw new InvalidManifestException(
            "The capability network:outbound names neither a host nor a tcp target. The list is"
                + " compiled into the egress proxy's allowlist, and an empty one would be a plugin"
                + " asking for the internet (ADR-0027).");
      }
      capabilities.add(
          new PluginManifest.Capability(
              id, string(capability, "reason", true), hosts, tcp, strings(capability, "events")));
    }

    List<PluginManifest.Setting> settings = new ArrayList<>();
    for (Map<?, ?> setting : sequence(node, "settings", false)) {
      reject(setting, "spec.settings[]", Set.of("key", "type", "required", "values", "default", "label"));
      String type = string(setting, "type", true);
      if (!SETTING_TYPES.contains(type)) {
        throw new InvalidManifestException(
            "The setting '"
                + string(setting, "key", true)
                + "' is of type '"
                + type
                + "'; the types are "
                + String.join(", ", SETTING_TYPES.stream().sorted().toList()));
      }
      List<String> values = strings(setting, "values");
      if ("enum".equals(type) && values.isEmpty()) {
        throw new InvalidManifestException(
            "The enum setting '" + string(setting, "key", true) + "' lists no values to choose from");
      }
      settings.add(
          new PluginManifest.Setting(
              string(setting, "key", true),
              type,
              Boolean.TRUE.equals(setting.get("required")),
              values,
              string(setting, "default", false),
              text(setting, "label")));
    }

    Map<?, ?> health = mapping(node, "health", false);
    Map<?, ?> resources = mapping(node, "resources", false);
    return new PluginManifest.Spec(
        string(node, "contract", true),
        parsed,
        List.copyOf(ports),
        List.copyOf(capabilities),
        List.copyOf(settings),
        health == null
            ? null
            : new PluginManifest.Health(
                string(health, "endpoint", true), integer(health, "intervalSeconds", 60)),
        resources == null
            ? null
            : new PluginManifest.Resources(
                integer(resources, "memoryMiB", 0), integer(resources, "timeoutSeconds", 0)));
  }

  /**
   * Refuses a key nobody defined.
   *
   * <p>A typo in {@code capabilities} would otherwise be a plugin that asks for nothing and is
   * granted nothing, discovered when it fails at runtime rather than when it is written.
   *
   * @param node the mapping to check
   * @param where what to call it in the refusal
   * @param known the keys this format has
   */
  private static void reject(Map<?, ?> node, String where, Set<String> known) {
    for (Object key : node.keySet()) {
      if (!known.contains(String.valueOf(key))) {
        throw new InvalidManifestException(
            where
                + " has no key '"
                + key
                + "'. A manifest's keys are refused rather than ignored, because a misspelt one is"
                + " something that would silently not have happened. The keys are: "
                + String.join(", ", known.stream().sorted().toList()));
      }
    }
  }

  private static String string(Map<?, ?> node, String key, boolean required) {
    Object value = node.get(key);
    if (value == null) {
      if (required) {
        throw new InvalidManifestException("The manifest has no " + key);
      }
      return null;
    }
    if (!(value instanceof String text) || text.isBlank()) {
      throw new InvalidManifestException(key + " is empty or is not text");
    }
    return text;
  }

  private static int integer(Map<?, ?> node, String key, int fallback) {
    Object value = node.get(key);
    if (value == null) {
      return fallback;
    }
    if (!(value instanceof Number number)) {
      throw new InvalidManifestException(key + " is not a number");
    }
    return number.intValue();
  }

  private static List<String> strings(Map<?, ?> node, String key) {
    Object value = node.get(key);
    if (value == null) {
      return List.of();
    }
    if (!(value instanceof List<?> list)) {
      throw new InvalidManifestException(key + " is a list, not a " + kindOf(value));
    }
    List<String> values = new ArrayList<>();
    for (Object element : list) {
      if (!(element instanceof String text) || text.isBlank()) {
        throw new InvalidManifestException(key + " holds an entry that is empty or is not text");
      }
      values.add(text);
    }
    return List.copyOf(values);
  }

  private static Map<String, String> text(Map<?, ?> node, String key) {
    Object value = node.get(key);
    if (value == null) {
      return Map.of();
    }
    if (!(value instanceof Map<?, ?> mapping)) {
      throw new InvalidManifestException(key + " is a mapping of language tag to text");
    }
    Map<String, String> translations = new LinkedHashMap<>();
    mapping.forEach(
        (language, line) -> {
          if (!(line instanceof String text) || text.isBlank()) {
            throw new InvalidManifestException(key + "." + language + " is empty or is not text");
          }
          translations.put(String.valueOf(language).toLowerCase(Locale.ROOT), text);
        });
    return Map.copyOf(translations);
  }

  private static Map<?, ?> mapping(Map<?, ?> node, String key, boolean required) {
    Object value = node.get(key);
    if (value == null) {
      if (required) {
        throw new InvalidManifestException("The manifest has no " + key);
      }
      return null;
    }
    if (!(value instanceof Map<?, ?> mapping)) {
      throw new InvalidManifestException(key + " is a mapping, not a " + kindOf(value));
    }
    return mapping;
  }

  private static List<Map<?, ?>> sequence(Map<?, ?> node, String key, boolean required) {
    Object value = node.get(key);
    if (value == null) {
      if (required) {
        throw new InvalidManifestException("The manifest has no " + key);
      }
      return List.of();
    }
    if (!(value instanceof List<?> list)) {
      throw new InvalidManifestException(key + " is a list, not a " + kindOf(value));
    }
    List<Map<?, ?>> entries = new ArrayList<>();
    for (Object element : list) {
      if (!(element instanceof Map<?, ?> mapping)) {
        throw new InvalidManifestException(key + " holds an entry that is not a mapping");
      }
      entries.add(mapping);
    }
    return entries;
  }

  private static String kindOf(Object value) {
    if (value == null) {
      return "nothing";
    }
    if (value instanceof List<?>) {
      return "list";
    }
    if (value instanceof Map<?, ?>) {
      return "mapping";
    }
    return "value";
  }
}
