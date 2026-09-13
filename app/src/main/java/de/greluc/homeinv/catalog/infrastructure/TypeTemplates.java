/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv.catalog.infrastructure;

import de.greluc.homeinv.catalog.api.FieldConstraints;
import de.greluc.homeinv.catalog.api.FieldDataType;
import de.greluc.homeinv.catalog.api.TypeKind;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * The eight item type templates REQ-CORE-030 ships, read from the file that defines them.
 *
 * <h2>Why they are a file and not a class</h2>
 *
 * <p>{@code docs/reference/type-templates.yaml} is the source of truth, copied into the artifact by
 * the build — the same treatment {@code permissions.yaml} and {@code problem-types.yaml} get, and
 * for the same reason: a list of ninety fields that lives only in Java is a list that changes
 * without anybody reviewing the change. Here it is one file, in the directory a reviewer already
 * looks at, and the build fails if it is not valid.
 *
 * <h2>What a template is not</h2>
 *
 * <p>It is not a link. Importing one creates an ordinary item type with an ordinary published
 * version, and nothing afterwards remembers where it came from — which is what REQ-CORE-030's
 * "fully editable" means. A tenant that renames half the fields has a type of its own, and a later
 * change to this file does not reach it.
 */
@Component
@Slf4j
public class TypeTemplates {

  /** Where the build puts the file. */
  private static final String RESOURCE = "catalog/type-templates.yaml";

  private final Map<String, Template> byKey;

  /**
   * One template as the file defines it.
   *
   * @param key the stable key, which becomes the type's key on import
   * @param kind whether its items exist physically
   * @param labels the template's name per language tag, shown in the picker
   * @param fields the fields it brings, in the order the file lists them
   */
  public record Template(
      String key, TypeKind kind, Map<String, String> labels, List<TemplateField> fields) {}

  /**
   * One field of a template.
   *
   * @param key the attribute key
   * @param labels the field's name per language tag
   * @param dataType the kind of value
   * @param unit the unit a {@code quantity} carries, or {@code null}
   * @param searchable whether it is mirrored for filtering
   * @param sortable whether it is mirrored for ordering
   * @param facetable whether it is mirrored for counting
   * @param sensitive whether it is stored encrypted and gated on a permission
   */
  public record TemplateField(
      String key,
      Map<String, String> labels,
      FieldDataType dataType,
      String unit,
      boolean searchable,
      boolean sortable,
      boolean facetable,
      boolean sensitive) {

    /**
     * The constraints this field is created with.
     *
     * @return a unit for a {@code quantity}, nothing otherwise
     */
    public FieldConstraints constraints() {
      return unit == null
          ? FieldConstraints.NONE
          : new FieldConstraints(null, null, null, null, null, unit, null);
    }
  }

  /**
   * Reads and validates the templates at startup.
   *
   * <p>At startup and not on first use: a file that does not parse, or that states a field both
   * {@code sensitive} and searchable, is a packaging error, and the moment to find one is while the
   * process is starting rather than when somebody clicks "import".
   */
  @SuppressWarnings("unchecked")
  public TypeTemplates() {
    Map<String, Template> loaded = new LinkedHashMap<>();
    Map<String, Object> document;
    try (InputStream stream = new ClassPathResource(RESOURCE).getInputStream()) {
      document = new Yaml().load(stream);
    } catch (IOException unreadable) {
      throw new IllegalStateException(
          "The item type templates at " + RESOURCE + " could not be read. They are copied into "
              + "the artifact from docs/reference/type-templates.yaml by the build.",
          unreadable);
    }

    for (Map<String, Object> entry : (List<Map<String, Object>>) document.get("templates")) {
      String key = (String) entry.get("key");
      List<TemplateField> fields = new ArrayList<>();
      for (Map<String, Object> field : (List<Map<String, Object>>) entry.get("fields")) {
        TemplateField parsed =
            new TemplateField(
                (String) field.get("key"),
                labels(field.get("labels")),
                FieldDataType.ofToken((String) field.get("dataType")),
                (String) field.get("unit"),
                Boolean.TRUE.equals(field.get("searchable")),
                Boolean.TRUE.equals(field.get("sortable")),
                Boolean.TRUE.equals(field.get("facetable")),
                Boolean.TRUE.equals(field.get("sensitive")));
        if (parsed.sensitive()
            && (parsed.searchable() || parsed.sortable() || parsed.facetable())) {
          // The type editor refuses this combination, so a template stating it
          // would be a template that cannot be imported -- discovered by the
          // first tenant to try rather than by the build.
          throw new IllegalStateException(
              "Template '" + key + "' marks '" + parsed.key() + "' sensitive as well as "
                  + "searchable, sortable or facetable. A sensitive field is stored encrypted, so "
                  + "an index over it would hold ciphertext.");
        }
        fields.add(parsed);
      }
      loaded.put(
          key,
          new Template(
              key,
              TypeKind.valueOf((String) entry.get("kind")),
              labels(entry.get("labels")),
              List.copyOf(fields)));
    }
    this.byKey = Map.copyOf(loaded);
    log.info("Loaded {} item type template(s) from {}.", byKey.size(), RESOURCE);
  }

  /**
   * Every template, in the order the file lists them.
   *
   * @return the templates
   */
  public List<Template> all() {
    return List.copyOf(byKey.values());
  }

  /**
   * One template by key.
   *
   * @param key the template's key
   * @return the template, or {@code null} when no such template ships
   */
  public Template byKey(String key) {
    return byKey.get(key);
  }

  /**
   * A YAML mapping of language tag to text.
   *
   * @param node what the parser produced
   * @return the labels, never null
   */
  @SuppressWarnings("unchecked")
  private static Map<String, String> labels(Object node) {
    if (!(node instanceof Map<?, ?> mapping)) {
      return Map.of();
    }
    Map<String, String> labels = new LinkedHashMap<>();
    ((Map<String, Object>) mapping)
        .forEach((language, text) -> labels.put(language, String.valueOf(text)));
    return Map.copyOf(labels);
  }
}
