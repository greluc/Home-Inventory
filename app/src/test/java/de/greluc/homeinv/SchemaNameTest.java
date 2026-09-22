/*
 * SPDX-FileCopyrightText: Lucas Greuloch
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package de.greluc.homeinv;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * No two Java types may be published under one schema name (REQ-API-002).
 *
 * <h2>What this caught</h2>
 *
 * <p>springdoc names a schema after the <b>simple class name</b>, and two classes called the same
 * thing therefore publish one shape under one name. The other is lost silently — no warning, no
 * error, a document that describes one endpoint's body and lies about another's.
 *
 * <p>Writing the generated clients of {@code REQ-API-002} found four of them at once, on
 * 2026-09-21, in a contract that had passed every other gate:
 *
 * <ul>
 *   <li>{@code /api/v1/version} was documented as a <b>catalogue type version</b>, fields and all,
 *       because {@code catalog.TypeAdministration.VersionView} won the name;
 *   <li>creating a custom role was documented with the body for <b>assigning</b> one;
 *   <li>setting a field's visibility was documented with a visibility <b>rule</b>'s shape;
 *   <li>the insurance report was documented with the <b>valuation</b> report's.
 * </ul>
 *
 * <p>A generated client compiles happily against all four and is wrong at run time, which is the
 * failure this whole requirement exists to make impossible.
 *
 * <h2>How it decides</h2>
 *
 * <p>For every schema in the document, it counts the Java types declaring that simple name. More
 * than one is refused — <b>unless</b> the extra one is in {@link #NOT_PUBLISHED}, which names the
 * pairs where only one of the two can reach the document and says why for each.
 *
 * <p>No container: this reads files.
 */
@DisplayName("The published schema names")
class SchemaNameTest {

  private static final Path DOCUMENT = Path.of("..", "api", "openapi.yaml");
  private static final Path SOURCES = Path.of("src", "main", "java");

  /** A top-level schema entry under {@code components.schemas}. */
  private static final Pattern SCHEMA = Pattern.compile("^    ([A-Za-z0-9_]+):$", Pattern.MULTILINE);

  /** Any type declaration, nested ones included. */
  private static final Pattern DECLARATION =
      Pattern.compile(
          "\\b(?:public\\s+|private\\s+|protected\\s+)?(?:static\\s+)?(?:final\\s+)?"
              + "(?:record|class|interface|enum)\\s+([A-Z][A-Za-z0-9_]*)");

  /**
   * The declarations that share a published name and cannot themselves be published.
   *
   * <p>Two pairs, and in both the REST layer keeps a wire type of its own <b>on purpose</b> — the
   * mapper beside each one says "maps the port's view onto the wire", which is what lets the wire
   * shape diverge from the port's later without a block noticing. The port's type is returned by no
   * controller, so it never reaches the document and cannot win the name.
   *
   * <p>The key is the schema name; the value is the file that declares the one that is <b>not</b>
   * published. A third entry is a decision, not a formality: the alternative to listing one is
   * renaming a type, and that is usually the better answer.
   */
  private static final Map<String, String> NOT_PUBLISHED =
      Map.of(
          "AccountView", "identity/api/AccountAdministration.java",
          "Started", "identity/api/FederatedSignIn.java");

  @Test
  @DisplayName("are each declared by exactly one type")
  void noTwoTypesShareAPublishedName() throws IOException {
    Set<String> published = new LinkedHashSet<>();
    Matcher schemas = SCHEMA.matcher(Files.readString(DOCUMENT, StandardCharsets.UTF_8));
    while (schemas.find()) {
      published.add(schemas.group(1));
    }
    assertThat(published).as("the document has schemas to check").isNotEmpty();

    Map<String, List<String>> declaredBy = new TreeMap<>();
    try (Stream<Path> sources = Files.walk(SOURCES)) {
      sources
          .filter(path -> path.toString().endsWith(".java"))
          .forEach(
              path -> {
                String relative = SOURCES.relativize(path).toString().replace('\\', '/')
                    .replace("de/greluc/homeinv/", "");
                Matcher types = DECLARATION.matcher(read(path));
                while (types.find()) {
                  declaredBy
                      .computeIfAbsent(types.group(1), ignored -> new ArrayList<>())
                      .add(relative);
                }
              });
    }

    Map<String, List<String>> collisions = new LinkedHashMap<>();
    for (String name : published) {
      List<String> files = declaredBy.getOrDefault(name, List.of());
      List<String> contenders = new ArrayList<>(new LinkedHashSet<>(files));
      contenders.remove(NOT_PUBLISHED.get(name));
      if (contenders.size() > 1) {
        collisions.put(name, contenders);
      }
    }

    assertThat(collisions)
        .as(
            "springdoc names a schema after the simple class name, so two types with one name "
                + "publish one shape and lose the other -- silently, in a document every generated "
                + "client is built from. Rename one of them, or, if only one can reach the "
                + "document, add it to NOT_PUBLISHED with the reason")
        .isEmpty();
  }

  @Test
  @DisplayName("keep their exception list honest")
  void everyExceptionNamesAFileThatExists() {
    List<String> missing = new ArrayList<>();
    NOT_PUBLISHED.forEach(
        (schema, file) -> {
          if (!Files.exists(SOURCES.resolve("de/greluc/homeinv").resolve(file))) {
            missing.add(schema + " -> " + file);
          }
        });

    assertThat(missing)
        .as(
            "an exception for a file nobody has any more is an exception nobody will read, and it "
                + "hides the next collision under the same name")
        .isEmpty();
  }

  private static String read(Path path) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException unreadable) {
      throw new UncheckedIOException(unreadable);
    }
  }
}
